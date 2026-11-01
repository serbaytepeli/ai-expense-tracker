import { fireEvent, renderRouter, screen } from 'expo-router/testing-library';

import tr from '../src/i18n/locales/tr.json';

// Renders the real app/ directory (root layout + tab layout + screens) in memory.
const APP_DIR = './app';

const TABS = [
  { key: 'home', testID: 'tab-home', pathname: '/' },
  { key: 'expenses', testID: 'tab-expenses', pathname: '/expenses' },
  { key: 'report', testID: 'tab-report', pathname: '/report' },
  { key: 'settings', testID: 'tab-settings', pathname: '/settings' },
] as const;

describe('tab navigation', () => {
  it('starts on the home tab', async () => {
    await renderRouter(APP_DIR);

    expect(screen).toHavePathname('/');
    expect(screen.getByText(tr.screens.home.placeholder)).toBeOnTheScreen();
  });

  it('shows the four tabs with labels from the Turkish translation file', async () => {
    await renderRouter(APP_DIR);

    for (const tab of TABS) {
      const button = screen.getByTestId(tab.testID);
      expect(button).toBeOnTheScreen();
      expect(button).toHaveTextContent(tr.tabs[tab.key]);
    }
  });

  it.each(TABS.filter((tab) => tab.key !== 'home'))(
    'switches to the $key tab and back to home',
    async ({ key, testID, pathname }) => {
      await renderRouter(APP_DIR);

      await fireEvent.press(screen.getByTestId(testID));
      expect(screen).toHavePathname(pathname);
      expect(screen.getByText(tr.screens[key].placeholder)).toBeOnTheScreen();
      expect(screen.getByRole('header', { name: tr.screens[key].title })).toBeOnTheScreen();

      await fireEvent.press(screen.getByTestId('tab-home'));
      expect(screen).toHavePathname('/');
      expect(screen.getByText(tr.screens.home.placeholder)).toBeOnTheScreen();
    },
  );
});
