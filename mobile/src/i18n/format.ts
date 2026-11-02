import type { TFunction } from 'i18next';

import { ApiError, NETWORK_ERROR } from '../api/errors';
import type { YearMonth } from '../features/expenses/dates';

const MONTH_KEYS = [
  'months.1',
  'months.2',
  'months.3',
  'months.4',
  'months.5',
  'months.6',
  'months.7',
  'months.8',
  'months.9',
  'months.10',
  'months.11',
  'months.12',
] as const;

export function monthName(t: TFunction, month: number): string {
  return t(MONTH_KEYS[month - 1]);
}

/** "Kasım 2026" */
export function formatYearMonth(t: TFunction, value: YearMonth): string {
  return `${monthName(t, value.month)} ${value.year}`;
}

/** "2026-10-28" -> "28 Ekim 2026" */
export function formatDate(t: TFunction, iso: string): string {
  const [year, month, day] = iso.split('-').map(Number);
  return `${day} ${monthName(t, month)} ${year}`;
}

const KNOWN_CODES = [
  'INVALID_CREDENTIALS',
  'EMAIL_TAKEN',
  'RATE_LIMITED',
  'VALIDATION_ERROR',
  'CATEGORY_ARCHIVED',
  'NOT_FOUND',
  'INTERNAL_ERROR',
] as const;
type KnownCode = (typeof KNOWN_CODES)[number];

/** Turkish message for any error from the API client. */
export function errorMessage(t: TFunction, error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === NETWORK_ERROR) {
      return t('errors.NETWORK');
    }
    if ((KNOWN_CODES as readonly string[]).includes(error.code)) {
      return t(`errors.${error.code as KnownCode}`);
    }
    if (error.status === 401) {
      return t('errors.SESSION_ENDED');
    }
    if (error.status >= 500) {
      return t('errors.INTERNAL_ERROR');
    }
  }
  return t('errors.unknown');
}
