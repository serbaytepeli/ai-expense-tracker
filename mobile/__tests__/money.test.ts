import {
  formatMoney,
  parseAmountInput,
  sumAmounts,
  toInputText,
} from '../src/features/expenses/money';

describe('amount input', () => {
  it.each([
    ['1.250,50', '1250.50'], // T2.2 AC
    ['1250,50', '1250.50'],
    ['149,9', '149.90'],
    ['5', '5.00'],
    ['5,', '5.00'],
    ['12.5', '12.50'],
    ['1.250', '1250.00'],
    ['1.000.000', '1000000.00'],
    ['0,01', '0.01'],
    ['007', '7.00'],
    [' 1 250,00 ₺', '1250.00'],
    ['999.999.999.999,99', '999999999999.99'],
  ])('%s -> %s', (input, expected) => {
    expect(parseAmountInput(input)).toBe(expected);
  });

  it.each([
    '',
    '0',
    '0,00',
    '-1',
    '1,234',
    '1e3',
    'abc',
    '1,2,3',
    '12.345,6.7',
    '1000000000000',
    '1.25.0',
  ])('rejects %p without rounding', (input) => {
    expect(parseAmountInput(input)).toBeNull();
  });
});

describe('money display', () => {
  it('formats in Turkish style with the lira sign', () => {
    expect(formatMoney('1250.50')).toBe('₺1.250,50');
    expect(formatMoney('0.05')).toBe('₺0,05');
    expect(formatMoney('999999999999.99')).toBe('₺999.999.999.999,99');
    expect(toInputText('1250.50')).toBe('1.250,50');
  });

  it('adds amounts exactly (no floating point)', () => {
    expect(sumAmounts(['0.10', '0.20'])).toBe('0.30');
    expect(sumAmounts([])).toBe('0.00');
    expect(sumAmounts(['999999999999.99', '999999999999.99'])).toBe('1999999999999.98');
    expect(sumAmounts(['149.90', '5.00', '0.01'])).toBe('154.91');
  });
});
