import '../src/i18n';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack, ThemeProvider } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError } from '../src/api/errors';
import { LoadingView } from '../src/components/ui';
import { useSessionStore } from '../src/features/auth/sessionStore';
import { navigationTheme } from '../src/theme/colors';
import { useAppTheme } from '../src/theme/useAppTheme';

// Modals (expense/new, expense/[id]) always open on top of the tabs, also from a deep link.
export const unstable_settings = { anchor: '(tabs)' };

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Client errors (404, 401 ...) do not get better by retrying.
        retry: (count, error) =>
          !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 2,
      },
    },
  });
}

export default function RootLayout() {
  const { scheme } = useAppTheme();
  const { t } = useTranslation();
  const [queryClient] = useState(createQueryClient);
  const status = useSessionStore((s) => s.status);
  const restore = useSessionStore((s) => s.restore);

  useEffect(() => {
    void restore();
  }, [restore]);

  // Another user may sign in next: drop everything cached for the previous one.
  useEffect(() => {
    if (status === 'signedOut') {
      queryClient.clear();
    }
  }, [status, queryClient]);

  const signedIn = status === 'signedIn';
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider value={navigationTheme(scheme)}>
        {status === 'loading' ? (
          <LoadingView />
        ) : (
          <Stack screenOptions={{ headerShown: false }}>
            <Stack.Protected guard={signedIn}>
              <Stack.Screen name="(tabs)" />
              <Stack.Screen
                name="expense/new"
                options={{ headerShown: true, presentation: 'modal', title: t('expenses.add') }}
              />
              <Stack.Screen
                name="expense/[id]"
                options={{ headerShown: true, presentation: 'modal', title: t('expenses.edit') }}
              />
            </Stack.Protected>
            <Stack.Protected guard={!signedIn}>
              <Stack.Screen name="login" />
              <Stack.Screen name="register" />
            </Stack.Protected>
          </Stack>
        )}
        <StatusBar style="auto" />
      </ThemeProvider>
    </QueryClientProvider>
  );
}
