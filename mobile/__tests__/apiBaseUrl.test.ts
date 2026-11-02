import { resolveApiBaseUrl } from '../src/config/apiBaseUrl';

describe('API base URL', () => {
  it('uses EXPO_PUBLIC_API_BASE_URL when set', () => {
    expect(resolveApiBaseUrl('https://api.example.com/api/v1/', '192.168.1.20:8081')).toBe(
      'https://api.example.com/api/v1',
    );
  });

  it('otherwise uses the computer that serves the bundle (Expo Go on a phone in the same Wi-Fi)', () => {
    expect(resolveApiBaseUrl(undefined, '192.168.1.20:8081')).toBe(
      'http://192.168.1.20:8080/api/v1',
    );
    expect(resolveApiBaseUrl('  ', '10.0.0.5:8081')).toBe('http://10.0.0.5:8080/api/v1');
  });

  it('falls back to this machine', () => {
    expect(resolveApiBaseUrl(undefined, undefined)).toBe('http://127.0.0.1:8080/api/v1');
  });
});
