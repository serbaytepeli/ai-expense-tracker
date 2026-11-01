import 'i18next';

import type tr from './locales/tr.json';

// Typed translation keys: t('screens.home.titel') is a TypeScript error.
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'translation';
    resources: {
      translation: typeof tr;
    };
  }
}
