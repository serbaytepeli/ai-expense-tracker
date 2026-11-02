import type { ExpoConfig } from 'expo/config';

// App configuration (docs/architecture.md section 4). Store identifiers (iOS bundle ID,
// Android package) are added with the store release phase after the MVP (D-11).
const config: ExpoConfig = {
  name: 'AI Expense Tracker',
  slug: 'ai-expense-tracker',
  version: '0.1.0',
  scheme: 'aiexpense',
  orientation: 'portrait',
  icon: './assets/icon.png',
  // Follow the device's light/dark setting.
  userInterfaceStyle: 'automatic',
  platforms: ['ios', 'android'],
  ios: {
    supportsTablet: true,
  },
  android: {
    adaptiveIcon: {
      backgroundColor: '#E6F4FE',
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
    predictiveBackGestureEnabled: false,
  },
  plugins: [
    'expo-router',
    'expo-status-bar',
    'expo-localization',
    'expo-secure-store',
    '@react-native-community/datetimepicker',
  ],
};

export default config;
