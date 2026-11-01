import { DarkTheme, DefaultTheme, type Theme } from 'expo-router';

export type ColorScheme = 'light' | 'dark';

export interface AppColors {
  background: string;
  surface: string;
  text: string;
  textMuted: string;
  primary: string;
  border: string;
  tabInactive: string;
}

// Text/background pairs meet WCAG AA contrast (4.5:1) in both schemes.
export const palettes: Record<ColorScheme, AppColors> = {
  light: {
    background: '#F7F8FA',
    surface: '#FFFFFF',
    text: '#111827',
    textMuted: '#4B5563',
    primary: '#0F766E',
    border: '#E5E7EB',
    tabInactive: '#6B7280',
  },
  dark: {
    background: '#0B0F14',
    surface: '#151B23',
    text: '#F3F4F6',
    textMuted: '#C2C8D0',
    primary: '#2DD4BF',
    border: '#263040',
    tabInactive: '#9CA3AF',
  },
};

/** React Navigation theme (headers, tab bar, screen background) built from the palette. */
export function navigationTheme(scheme: ColorScheme): Theme {
  const base = scheme === 'dark' ? DarkTheme : DefaultTheme;
  const colors = palettes[scheme];
  return {
    ...base,
    colors: {
      ...base.colors,
      primary: colors.primary,
      background: colors.background,
      card: colors.surface,
      text: colors.text,
      border: colors.border,
    },
  };
}
