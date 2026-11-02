/** Dates are "YYYY-MM-DD" strings in the user's local calendar, as the API expects. */

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** Today on this device (the user registered with the device's time zone). */
export function todayIso(now: Date = new Date()): string {
  return toIsoDate(now);
}

export function toIsoDate(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Local midnight of an ISO date, for the date picker. */
export function fromIsoDate(iso: string): Date {
  const [year, month, day] = iso.split('-').map(Number);
  return new Date(year, month - 1, day);
}

export interface YearMonth {
  year: number;
  month: number; // 1-12
}

export function currentMonth(now: Date = new Date()): YearMonth {
  return { year: now.getFullYear(), month: now.getMonth() + 1 };
}

export function shiftMonth({ year, month }: YearMonth, delta: number): YearMonth {
  const index = year * 12 + (month - 1) + delta;
  return { year: Math.floor(index / 12), month: (index % 12) + 1 };
}

/** "2026-11" for the API's month parameter. */
export function toMonthParam({ year, month }: YearMonth): string {
  return `${year}-${pad(month)}`;
}

export function isSameMonth(a: YearMonth, b: YearMonth): boolean {
  return a.year === b.year && a.month === b.month;
}
