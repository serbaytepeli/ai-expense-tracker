import { getCalendars } from 'expo-localization';

import { apiRequest } from '../../api/client';
import type { AuthResponse } from '../../api/types';
import { useSessionStore } from './sessionStore';

export interface RegisterInput {
  displayName: string;
  email: string;
  password: string;
}

async function startSession(response: AuthResponse) {
  await useSessionStore.getState().signIn({
    accessToken: response.accessToken,
    refreshToken: response.refreshToken,
    user: response.user,
  });
}

export async function login(email: string, password: string): Promise<void> {
  const response = await apiRequest<AuthResponse>('/auth/login', {
    method: 'POST',
    body: { email: email.trim(), password },
    auth: false,
  });
  await startSession(response);
}

/** The device's IANA time zone decides what "today" is for this user (D-7). */
export async function register(input: RegisterInput): Promise<void> {
  const response = await apiRequest<AuthResponse>('/auth/register', {
    method: 'POST',
    body: {
      displayName: input.displayName.trim(),
      email: input.email.trim(),
      password: input.password,
      timeZone: getCalendars()[0]?.timeZone ?? undefined,
    },
    auth: false,
  });
  await startSession(response);
}

/** Ends the session on the server when reachable; the device forgets it in any case. */
export async function logout(): Promise<void> {
  try {
    await apiRequest<void>('/auth/logout', { method: 'POST' });
  } catch {
    // Offline or already revoked: still sign out locally.
  } finally {
    await useSessionStore.getState().signOut();
  }
}
