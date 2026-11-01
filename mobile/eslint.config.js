// ESLint flat config (Expo SDK 58). `npm run lint` fails on any error or warning.
const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
const prettierConfig = require('eslint-config-prettier/flat');
const i18next = require('eslint-plugin-i18next');

module.exports = defineConfig([
  expoConfig,
  {
    ignores: ['dist/*', 'coverage/*', '.expo/*', 'node_modules/*'],
  },
  {
    // CLAUDE.md: no hard-coded user-facing text; every string shown in the UI comes from
    // src/i18n/locales. Applies to screens, shared components and feature code.
    files: ['app/**/*.{ts,tsx}', 'src/components/**/*.{ts,tsx}', 'src/features/**/*.{ts,tsx}'],
    plugins: { i18next },
    rules: {
      'i18next/no-literal-string': [
        'error',
        {
          // Checks JSX text (<Text>Merhaba</Text>, <Text>{'Merhaba'}</Text>) and the props below.
          mode: 'jsx-only',
          // Only props whose value is shown to the user (or read by a screen reader).
          // Technical props (name, testID, accessibilityRole, style, ...) are not checked.
          'jsx-attributes': {
            include: [
              'title',
              'placeholder',
              'label',
              'message',
              'accessibilityLabel',
              'accessibilityHint',
              'alt',
              'options',
              'screenOptions',
            ],
          },
          // Inside navigation options: test IDs and icon names are not user-facing text.
          // Patterns must match the whole name.
          'object-properties': { exclude: ['[A-Z_-]+', '.*TestID', 'testID'] },
          callees: { exclude: ['t', 'i18n(ext)?', 'require', 'tabIcon'] },
        },
      ],
    },
  },
  {
    files: ['**/*.{ts,tsx}'],
    rules: {
      // CLAUDE.md: TypeScript strict, no `any`.
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },
  // Must stay last: turns off style rules that Prettier owns.
  prettierConfig,
]);
