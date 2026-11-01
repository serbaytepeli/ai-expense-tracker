import { render, screen } from '@testing-library/react-native';
import { useColorScheme } from 'react-native';

import { PlaceholderScreen } from '../src/components/PlaceholderScreen';
import { navigationTheme, palettes } from '../src/theme/colors';

jest.mock('react-native/Libraries/Utilities/useColorScheme', () => ({
  __esModule: true,
  default: jest.fn(),
}));

const mockedUseColorScheme = useColorScheme as jest.MockedFunction<typeof useColorScheme>;

describe('light and dark theme', () => {
  it.each(['light', 'dark'] as const)('uses the %s palette for screen colours', async (scheme) => {
    mockedUseColorScheme.mockReturnValue(scheme);

    await render(<PlaceholderScreen title="T" message="M" />);

    expect(screen.getByText('T')).toHaveStyle({ color: palettes[scheme].text });
    expect(screen.getByText('M')).toHaveStyle({ color: palettes[scheme].textMuted });
  });

  it('builds a dark navigation theme from the dark palette', () => {
    const theme = navigationTheme('dark');

    expect(theme.dark).toBe(true);
    expect(theme.colors.background).toBe(palettes.dark.background);
    expect(theme.colors.card).toBe(palettes.dark.surface);
    expect(navigationTheme('light').dark).toBe(false);
  });
});
