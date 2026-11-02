/**
 * Money is a decimal string end to end (CLAUDE.md rule 1): never a JS number, so no binary
 * floating point. The API format is "1250.50" (database-design.md section 2).
 */

const API_AMOUNT = /^[0-9]{1,12}(\.[0-9]{1,2})?$/;

/**
 * Turkish input to the API format: "1.250,50" -> "1250.50", "149,9" -> "149.90", "5" -> "5.00".
 * Dots are thousands separators and the comma is the decimal separator; without a comma, a
 * single dot followed by 1-2 digits is read as a decimal point ("12.5"), as many keyboards
 * offer only ".". Returns null for anything that is not a valid positive amount; never rounds.
 */
export function parseAmountInput(input: string): string | null {
  let value = input.replace(/[\s ₺]/g, '');
  if (value === '') {
    return null;
  }
  if (value.includes(',')) {
    if (!/^[0-9]{1,3}(\.[0-9]{3})*,[0-9]*$|^[0-9]+,[0-9]*$/.test(value)) {
      return null;
    }
    value = value.replace(/\./g, '').replace(',', '.');
  } else if (/^[0-9]+\.[0-9]{1,2}$/.test(value)) {
    // "12.5": decimal point.
  } else if (/^[0-9]{1,3}(\.[0-9]{3})+$/.test(value)) {
    value = value.replace(/\./g, '');
  } else if (!/^[0-9]+$/.test(value)) {
    return null;
  }
  value = value.replace(/\.$/, '');
  const [whole, fraction = ''] = value.split('.');
  const normalizedWhole = whole.replace(/^0+(?=\d)/, '');
  const candidate = `${normalizedWhole}.${fraction.padEnd(2, '0')}`;
  if (!API_AMOUNT.test(candidate) || /^0+\.00$/.test(candidate)) {
    return null;
  }
  return candidate;
}

/** "1250.50" -> "1.250,50" for an input field (no currency sign). */
export function toInputText(amount: string): string {
  const [whole, fraction = '00'] = amount.split('.');
  return `${groupThousands(whole)},${fraction.padEnd(2, '0').slice(0, 2)}`;
}

/** "1250.50" -> "₺1.250,50" (Turkish number format, display only). */
export function formatMoney(amount: string): string {
  const negative = amount.startsWith('-');
  return `${negative ? '-' : ''}₺${toInputText(negative ? amount.slice(1) : amount)}`;
}

/** Exact sum of API amounts, computed in kuruş with BigInt: ["0.10", "0.20"] -> "0.30". */
export function sumAmounts(amounts: readonly string[]): string {
  let cents = 0n;
  for (const amount of amounts) {
    const [whole, fraction = ''] = amount.split('.');
    cents += BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0').slice(0, 2));
  }
  const whole = cents / 100n;
  const fraction = (cents % 100n).toString().padStart(2, '0');
  return `${whole.toString()}.${fraction}`;
}

function groupThousands(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}
