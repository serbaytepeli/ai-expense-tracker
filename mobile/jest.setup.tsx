// Global Jest setup.

// Icon fonts are loaded through Metro's asset registry, which does not exist in Jest
// ("Module 1 is missing from the asset registry"). Icons are decoration, so tests render
// a plain stand-in that keeps the icon name for assertions if needed.
jest.mock('@expo/vector-icons/Ionicons', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  function MockIonicons({ name }: { name: string }) {
    return <Text testID={`icon-${name}`} />;
  }
  return { __esModule: true, default: MockIonicons };
});
