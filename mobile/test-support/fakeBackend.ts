import * as SecureStore from 'expo-secure-store';

import { setApiBaseUrlForTests } from '../src/api/client';
import { SESSION_KEY, useSessionStore } from '../src/features/auth/sessionStore';

export const BASE = 'http://test.local/api/v1';

export interface Call {
  method: string;
  path: string;
  query: Record<string, string>;
  headers: Record<string, string>;
  body: unknown;
}

type Handler = (
  call: Call,
) => { status: number; body?: unknown } | Promise<{ status: number; body?: unknown }>;

/** A fetch stand-in that records calls and answers from a handler. */
export function installFetch(handler: Handler): Call[] {
  setApiBaseUrlForTests(BASE);
  const calls: Call[] = [];
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const call: Call = {
      method: init?.method ?? 'GET',
      path: url.pathname.replace('/api/v1', ''),
      query: Object.fromEntries(url.searchParams.entries()),
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    };
    calls.push(call);
    const { status, body } = await handler(call);
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
    });
  }) as typeof fetch;
  return calls;
}

export const USER = {
  id: 'user-1',
  email: 'ali@example.com',
  displayName: 'Ali',
  currency: 'TRY',
  timeZone: 'Europe/Istanbul',
  locale: 'tr-TR',
};

export async function resetSession() {
  (SecureStore as unknown as { __reset: () => void }).__reset();
  useSessionStore.setState({ status: 'signedOut', session: null });
}

export async function signInAs(accessToken = 'access-1', refreshToken = 'refresh-1') {
  await useSessionStore.getState().signIn({ accessToken, refreshToken, user: USER });
}

export async function savedSession(): Promise<unknown> {
  const raw = await SecureStore.getItemAsync(SESSION_KEY);
  return raw ? JSON.parse(raw) : null;
}

export function problem(
  status: number,
  code: string,
  errors?: { field: string; message: string }[],
) {
  return { status, body: { status, code, errors, requestId: 'req-1' } };
}
