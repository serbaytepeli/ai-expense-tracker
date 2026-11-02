import { resolveApiBaseUrl } from '../config/apiBaseUrl';
import { useSessionStore } from '../features/auth/sessionStore';
import { ApiError, NETWORK_ERROR, toApiError } from './errors';
import type { TokenResponse } from './types';

type QueryValue = string | number | undefined | null;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, QueryValue>;
  /** false for register/login/refresh: no token, no refresh on 401. */
  auth?: boolean;
}

const TIMEOUT_MS = 15_000;
/** 401 codes after which a refresh can help; anything else ends the session or is final. */
const REFRESHABLE = new Set(['UNAUTHORIZED', 'TOKEN_EXPIRED']);
/** Refresh answers that mean the session is over (architecture.md section 3.6). */
const SESSION_OVER = new Set([
  'TOKEN_REUSED',
  'SESSION_REVOKED',
  'TOKEN_EXPIRED',
  'UNAUTHORIZED',
  'TOKEN_ALREADY_ROTATED',
]);

let baseUrl = resolveApiBaseUrl();
/** The refresh in progress; concurrent 401s wait for this one instead of starting another. */
let refreshing: Promise<boolean> | null = null;

/** Tests only. */
export function setApiBaseUrlForTests(url: string) {
  baseUrl = url;
}

function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const parts = Object.entries(query ?? {})
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
  return `${baseUrl}${path}${parts.length > 0 ? `?${parts.join('&')}` : ''}`;
}

async function send(
  path: string,
  options: RequestOptions,
  accessToken?: string,
): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    return await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    });
  } catch {
    throw new ApiError(0, NETWORK_ERROR);
  } finally {
    clearTimeout(timer);
  }
}

async function parse<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * Exchanges the refresh token once. Returns true when a usable access token is in the session
 * store afterwards, false when the session has ended (the user is signed out).
 */
async function refreshSession(usedRefreshToken: string): Promise<boolean> {
  const store = useSessionStore.getState();
  const response = await send('/auth/refresh', {
    method: 'POST',
    body: { refreshToken: usedRefreshToken },
  });
  if (response.ok) {
    const tokens = await parse<TokenResponse>(response);
    await store.updateTokens(tokens.accessToken, tokens.refreshToken);
    return true;
  }
  const error = await toApiError(response);
  if (error.code === 'TOKEN_ALREADY_ROTATED') {
    // Another refresh already rotated this token; continue if we hold the newer pair.
    const current = useSessionStore.getState().session?.refreshToken;
    if (current && current !== usedRefreshToken) {
      return true;
    }
  }
  if (response.status === 401 && SESSION_OVER.has(error.code)) {
    await useSessionStore.getState().signOut();
    return false;
  }
  // Rate limit, server or network trouble: keep the session, fail this request.
  throw error;
}

function refreshOnce(usedRefreshToken: string): Promise<boolean> {
  if (!refreshing) {
    refreshing = refreshSession(usedRefreshToken).finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

/**
 * Calls the backend. On a 401 for an expired or invalid access token it refreshes the session
 * once (shared by concurrent requests) and repeats the request once.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (options.auth === false) {
    const response = await send(path, options);
    if (!response.ok) {
      throw await toApiError(response);
    }
    return parse<T>(response);
  }

  const session = useSessionStore.getState().session;
  if (!session) {
    throw new ApiError(401, 'UNAUTHORIZED');
  }
  let response = await send(path, options, session.accessToken);
  if (response.status === 401) {
    const error = await toApiError(response);
    if (!REFRESHABLE.has(error.code)) {
      // SESSION_REVOKED (logout elsewhere, token reuse): the session is over.
      await useSessionStore.getState().signOut();
      throw error;
    }
    const latest = useSessionStore.getState().session;
    // Another request may already have refreshed while this one was in flight.
    const fresh =
      latest && latest.accessToken !== session.accessToken
        ? true
        : await refreshOnce(session.refreshToken);
    const current = useSessionStore.getState().session;
    if (!fresh || !current) {
      throw error;
    }
    response = await send(path, options, current.accessToken);
    if (response.status === 401) {
      await useSessionStore.getState().signOut();
    }
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  return parse<T>(response);
}
