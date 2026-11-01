import i18n, { DEFAULT_LANGUAGE, resolveLanguage } from '../src/i18n';
import tr from '../src/i18n/locales/tr.json';

function leafValues(value: unknown): string[] {
  if (typeof value === 'string') {
    return [value];
  }
  return Object.values(value as Record<string, unknown>).flatMap(leafValues);
}

describe('i18n', () => {
  it('is initialised in Turkish', () => {
    expect(i18n.isInitialized).toBe(true);
    expect(i18n.language).toBe('tr');
    expect(i18n.t('tabs.expenses')).toBe(tr.tabs.expenses);
  });

  it('falls back to Turkish for unsupported or unknown device languages', () => {
    expect(resolveLanguage('tr')).toBe('tr');
    expect(resolveLanguage('en')).toBe(DEFAULT_LANGUAGE);
    expect(resolveLanguage(null)).toBe(DEFAULT_LANGUAGE);
  });

  it('has no empty translations', () => {
    for (const text of leafValues(tr)) {
      expect(text.trim()).not.toHaveLength(0);
    }
  });
});
