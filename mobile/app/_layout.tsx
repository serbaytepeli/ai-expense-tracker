import '../src/i18n';

import { Stack, ThemeProvider } from 'expo-router';
import { StatusBar } from 'expo-status-bar';

import { navigationTheme } from '../src/theme/colors';
import { useAppTheme } from '../src/theme/useAppTheme';

export default function RootLayout() {
  const { scheme } = useAppTheme();

  return (
    <ThemeProvider value={navigationTheme(scheme)}>
      <Stack screenOptions={{ headerShown: false }} />
      <StatusBar style="auto" />
    </ThemeProvider>
  );
}
