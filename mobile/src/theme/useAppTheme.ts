import { useColorScheme } from 'react-native';

import { palettes, type AppColors, type ColorScheme } from './colors';

export interface AppTheme {
  scheme: ColorScheme;
  colors: AppColors;
}

/** Current light/dark palette, following the device setting. */
export function useAppTheme(): AppTheme {
  const scheme: ColorScheme = useColorScheme() === 'dark' ? 'dark' : 'light';
  return { scheme, colors: palettes[scheme] };
}
