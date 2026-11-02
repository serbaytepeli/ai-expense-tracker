import * as SecureStore from 'expo-secure-store';
import { create } from 'zustand';

import type { User } from '../../api/types';

export interface Session {
  accessToken: string;
  refreshToken: string;
  user: User;
}

type Status = 'loading' | 'signedOut' | 'signedIn';

interface SessionState {
  status: Status;
  session: Session | null;
  /** Reads the saved session once at app start. */
  restore: () => Promise<void>;
  signIn: (session: Session) => Promise<void>;
  updateTokens: (accessToken: string, refreshToken: string) => Promise<void>;
  signOut: () => Promise<void>;
}

/** Tokens live only in the device's secure storage (Keychain / Keystore), never AsyncStorage. */
export const SESSION_KEY = 'aiexpense.session';

function isSession(value: unknown): value is Session {
  const s = value as Session | null;
  return (
    typeof s === 'object' &&
    s !== null &&
    typeof s.accessToken === 'string' &&
    typeof s.refreshToken === 'string' &&
    typeof s.user === 'object' &&
    s.user !== null &&
    typeof s.user.id === 'string'
  );
}

export const useSessionStore = create<SessionState>((set, get) => ({
  status: 'loading',
  session: null,

  restore: async () => {
    try {
      const saved = await SecureStore.getItemAsync(SESSION_KEY);
      const parsed: unknown = saved ? JSON.parse(saved) : null;
      if (isSession(parsed)) {
        set({ status: 'signedIn', session: parsed });
        return;
      }
    } catch {
      // Unreadable or corrupt: start signed out.
    }
    set({ status: 'signedOut', session: null });
  },

  signIn: async (session) => {
    await SecureStore.setItemAsync(SESSION_KEY, JSON.stringify(session));
    set({ status: 'signedIn', session });
  },

  updateTokens: async (accessToken, refreshToken) => {
    const current = get().session;
    if (!current) {
      return;
    }
    const session = { ...current, accessToken, refreshToken };
    set({ session });
    await SecureStore.setItemAsync(SESSION_KEY, JSON.stringify(session));
  },

  signOut: async () => {
    set({ status: 'signedOut', session: null });
    try {
      await SecureStore.deleteItemAsync(SESSION_KEY);
    } catch {
      // Nothing saved: already signed out.
    }
  },
}));
