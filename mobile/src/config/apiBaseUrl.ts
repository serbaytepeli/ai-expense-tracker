import Constants from 'expo-constants';

const BACKEND_PORT = 8080;

/**
 * Backend address (README, "EXPO_PUBLIC_API_BASE_URL"). Not secret: bundled into the app.
 *
 * Without the variable, a development build uses the computer that serves the bundle
 * (Metro's host, e.g. 192.168.1.20:8081 -> http://192.168.1.20:8080/api/v1), so Expo Go on a
 * phone in the same Wi-Fi reaches the backend running on that computer.
 */
export function resolveApiBaseUrl(
  configured: string | undefined = process.env.EXPO_PUBLIC_API_BASE_URL,
  hostUri: string | undefined = Constants.expoConfig?.hostUri,
): string {
  if (configured && configured.trim() !== '') {
    return configured.trim().replace(/\/+$/, '');
  }
  const host = hostUri?.split(':')[0];
  return `http://${host && host.length > 0 ? host : '127.0.0.1'}:${BACKEND_PORT}/api/v1`;
}
