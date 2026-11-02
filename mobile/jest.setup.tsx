// Global Jest setup.

// Icon fonts are loaded through Metro's asset registry, which does not exist in Jest
// ("Module 1 is missing from the asset registry"). Icons are decoration, so tests render
// a plain stand-in that keeps the icon name for assertions if needed.
jest.mock('@expo/vector-icons/Ionicons', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  function MockIonicons({ name }: { name: string }) {
    return <Text testID={`icon-${name}`} />;
  }
  // Every icon name counts as known, like the real glyph map for valid names.
  const glyphMap = new Proxy({}, { has: () => true });
  return { __esModule: true, default: Object.assign(MockIonicons, { glyphMap }) };
});

// Secure storage (Keychain / Keystore) does not exist in Jest: an in-memory stand-in.
jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    __esModule: true,
    getItemAsync: jest.fn(async (key: string) => store.get(key) ?? null),
    setItemAsync: jest.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    deleteItemAsync: jest.fn(async (key: string) => {
      store.delete(key);
    }),
    __reset: () => store.clear(),
  };
});

// The native date picker is replaced by a plain view; tests choose dates with the chips.
jest.mock('@react-native-community/datetimepicker', () => {
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  function MockDateTimePicker({ testID }: { testID?: string }) {
    return <View testID={testID} />;
  }
  return { __esModule: true, default: MockDateTimePicker };
});
