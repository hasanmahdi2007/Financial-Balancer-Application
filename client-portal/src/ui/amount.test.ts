import { describe, expect, it } from 'vitest';
import { formatMoney, isAmount, sameAmount } from './amount';

describe('amounts', () => {
  it('accepts what money looks like', () => {
    for (const good of ['0', '350', '350.5', '350.50', '1234567.89']) expect(isAmount(good), good).toBe(true);
  });

  it('refuses the things people type that are not amounts', () => {
    // "1,000" and "1 000" mean different amounts in different countries, so neither is guessed at.
    for (const bad of ['', 'about 400', '1,000', '1 000', '-20', '350.505', '12345678', '$350'])
      expect(isAmount(bad), bad).toBe(false);
  });

  it('groups thousands without touching the digits the server sent', () => {
    expect(formatMoney('1570.00')).toBe('$1,570.00');
    expect(formatMoney('0.00')).toBe('$0.00');
    expect(formatMoney('999.99')).toBe('$999.99');
    expect(formatMoney('12000.00')).toBe('$12,000.00');
    expect(formatMoney('-50.00')).toBe('-$50.00');
  });

  it('keeps the two decimal places rather than rounding them away', () => {
    // The server sends money at scale 2 on purpose. Anything that reformatted through a float here
    // would eventually disagree with the plan on screen beside it.
    expect(formatMoney('1570.10')).toBe('$1,570.10');
    expect(formatMoney('0.01')).toBe('$0.01');
  });

  it('shows a figure it cannot parse exactly as it arrived', () => {
    expect(formatMoney('not a number')).toBe('not a number');
  });
});

describe('telling a changed answer from one left at the suggestion', () => {
  it('treats the ways of writing one amount as that amount', () => {
    expect(sameAmount('350', '350.00')).toBe(true);
    expect(sameAmount('350.5', '350.50')).toBe(true);
    expect(sameAmount('0350.00', '350')).toBe(true);
    expect(sameAmount(' 350 ', '350.00')).toBe(true);
  });

  it('notices a real change, however small', () => {
    expect(sameAmount('350.01', '350.00')).toBe(false);
    expect(sameAmount('35', '350')).toBe(false);
  });
});
