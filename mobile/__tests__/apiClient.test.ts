import { apiRequest } from '../src/api/client';
import { ApiError } from '../src/api/errors';
import { useSessionStore } from '../src/features/auth/sessionStore';
import {
  installFetch,
  problem,
  resetSession,
  savedSession,
  signInAs,
  type Call,
} from '../test-support/fakeBackend';

// T1.4 AC: one refresh per 401 and a single retry; concurrent 401s share one refresh;
// TOKEN_ALREADY_ROTATED continues with a newer token; TOKEN_REUSED / SESSION_REVOKED sign out.

const bearer = (call: Call) => call.headers.Authorization;

beforeEach(async () => {
  await resetSession();
  await signInAs('access-old', 'refresh-old');
});

describe('API client', () => {
  it('sends the access token and parses JSON', async () => {
    const calls = installFetch(() => ({ status: 200, body: [{ id: 'c1' }] }));

    await expect(
      apiRequest('/categories', { query: { includeArchived: 'true' } }),
    ).resolves.toEqual([{ id: 'c1' }]);
    expect(bearer(calls[0])).toBe('Bearer access-old');
    expect(calls[0].query).toEqual({ includeArchived: 'true' });
  });

  it('refreshes once on an expired token, saves the new pair and repeats the request', async () => {
    const calls = installFetch((call) => {
      if (call.path === '/auth/refresh') {
        return {
          status: 200,
          body: { accessToken: 'access-new', refreshToken: 'refresh-new', expiresIn: 900 },
        };
      }
      return bearer(call) === 'Bearer access-new'
        ? { status: 200, body: { ok: true } }
        : problem(401, 'TOKEN_EXPIRED');
    });

    await expect(apiRequest('/expenses')).resolves.toEqual({ ok: true });

    expect(calls.map((c) => c.path)).toEqual(['/expenses', '/auth/refresh', '/expenses']);
    expect(calls[1].body).toEqual({ refreshToken: 'refresh-old' });
    expect(calls[1].headers.Authorization).toBeUndefined();
    expect(useSessionStore.getState().session?.accessToken).toBe('access-new');
    expect(await savedSession()).toMatchObject({
      accessToken: 'access-new',
      refreshToken: 'refresh-new',
    });
  });

  it('shares one refresh between concurrent 401s', async () => {
    let releaseRefresh: () => void = () => undefined;
    const refreshGate = new Promise<void>((resolve) => {
      releaseRefresh = resolve;
    });
    const calls = installFetch(async (call) => {
      if (call.path === '/auth/refresh') {
        await refreshGate;
        return {
          status: 200,
          body: { accessToken: 'access-new', refreshToken: 'refresh-new', expiresIn: 900 },
        };
      }
      return bearer(call) === 'Bearer access-new'
        ? { status: 200, body: { path: call.path } }
        : problem(401, 'TOKEN_EXPIRED');
    });

    const requests = Promise.all([apiRequest('/a'), apiRequest('/b'), apiRequest('/c')]);
    await new Promise((resolve) => setTimeout(resolve, 10));
    releaseRefresh();

    await expect(requests).resolves.toEqual([{ path: '/a' }, { path: '/b' }, { path: '/c' }]);
    expect(calls.filter((c) => c.path === '/auth/refresh')).toHaveLength(1);
  });

  it('uses a newer token when another request already refreshed', async () => {
    const calls = installFetch(async (call) => {
      if (call.path === '/slow' && bearer(call) === 'Bearer access-old') {
        // Meanwhile the session got a new pair (e.g. from another request).
        await useSessionStore.getState().updateTokens('access-new', 'refresh-new');
        return problem(401, 'TOKEN_EXPIRED');
      }
      return { status: 200, body: { ok: true } };
    });

    await expect(apiRequest('/slow')).resolves.toEqual({ ok: true });
    expect(calls.map((c) => c.path)).toEqual(['/slow', '/slow']);
    expect(bearer(calls[1])).toBe('Bearer access-new');
  });

  it('continues after TOKEN_ALREADY_ROTATED when a newer pair is already stored', async () => {
    installFetch(async (call) => {
      if (call.path === '/auth/refresh') {
        await useSessionStore.getState().updateTokens('access-new', 'refresh-new');
        return problem(401, 'TOKEN_ALREADY_ROTATED');
      }
      return bearer(call) === 'Bearer access-new'
        ? { status: 200, body: { ok: true } }
        : problem(401, 'TOKEN_EXPIRED');
    });

    await expect(apiRequest('/expenses')).resolves.toEqual({ ok: true });
    expect(useSessionStore.getState().status).toBe('signedIn');
  });

  it.each(['TOKEN_REUSED', 'SESSION_REVOKED', 'TOKEN_EXPIRED', 'TOKEN_ALREADY_ROTATED'])(
    'signs out when the refresh answers %s',
    async (code) => {
      installFetch((call) =>
        call.path === '/auth/refresh' ? problem(401, code) : problem(401, 'TOKEN_EXPIRED'),
      );

      await expect(apiRequest('/expenses')).rejects.toBeInstanceOf(ApiError);
      expect(useSessionStore.getState().status).toBe('signedOut');
      expect(await savedSession()).toBeNull();
    },
  );

  it('signs out without refreshing when the session was revoked', async () => {
    const calls = installFetch(() => problem(401, 'SESSION_REVOKED'));

    await expect(apiRequest('/expenses')).rejects.toMatchObject({ code: 'SESSION_REVOKED' });
    expect(calls).toHaveLength(1);
    expect(useSessionStore.getState().status).toBe('signedOut');
  });

  it('keeps the session when the refresh fails for a temporary reason', async () => {
    installFetch((call) =>
      call.path === '/auth/refresh'
        ? problem(503, 'SERVICE_UNAVAILABLE')
        : problem(401, 'TOKEN_EXPIRED'),
    );

    await expect(apiRequest('/expenses')).rejects.toMatchObject({ status: 503 });
    expect(useSessionStore.getState().status).toBe('signedIn');
  });

  it('turns problem+json into an ApiError with field errors', async () => {
    installFetch(() => problem(400, 'VALIDATION_ERROR', [{ field: 'amount', message: 'bad' }]));

    await expect(apiRequest('/expenses', { method: 'POST', body: {} })).rejects.toMatchObject({
      status: 400,
      code: 'VALIDATION_ERROR',
      fieldErrors: [{ field: 'amount', message: 'bad' }],
      requestId: 'req-1',
    });
  });

  it('reports network failures as NETWORK', async () => {
    globalThis.fetch = jest.fn(async () => {
      throw new TypeError('Network request failed');
    }) as typeof fetch;

    await expect(apiRequest('/expenses')).rejects.toMatchObject({ status: 0, code: 'NETWORK' });
    expect(useSessionStore.getState().status).toBe('signedIn');
  });
});
