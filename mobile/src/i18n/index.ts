import { createInstance } from 'i18next';
import { initReactI18next } from 'react-i18next';
import { getLocales } from 'expo-localization';

import tr from './locales/tr.json';

// The first release is Turkish only (D-6). Adding a language = adding a locale file here.
export const SUPPORTED_LANGUAGES = ['tr'] as const;
export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number];
export const DEFAULT_LANGUAGE: SupportedLanguage = 'tr';

export const resources = {
  tr: { translation: tr },
} as const;

/** Device language if supported, otherwise Turkish. */
export function resolveLanguage(
  deviceLanguage = getLocales()[0]?.languageCode ?? null,
): SupportedLanguage {
  return SUPPORTED_LANGUAGES.find((language) => language === deviceLanguage) ?? DEFAULT_LANGUAGE;
}

// A dedicated instance; initReactI18next registers it for useTranslation().
const i18n = createInstance();

if (!i18n.isInitialized) {
  void i18n.use(initReactI18next).init({
    resources,
    lng: resolveLanguage(),
    fallbackLng: DEFAULT_LANGUAGE,
    // Resources are bundled, so initialise synchronously (no flash of untranslated keys).
    initAsync: false,
    interpolation: { escapeValue: false },
  });
}

export default i18n;
