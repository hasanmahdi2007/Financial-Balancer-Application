/**
 * What counts as an amount of money, in one place.
 *
 * Amounts never become JavaScript numbers here. `Number("350.10")` is a binary float, which is the
 * representation the server's `Money` deliberately refuses; a client that parsed and re-formatted
 * would eventually disagree with the plan it is displaying. So an amount is validated as text and
 * sent as text, exactly as typed.
 */

/** Whole dollars or dollars and cents, never negative, up to seven digits. */
const AMOUNT = /^\d{1,7}(\.\d{1,2})?$/;

/**
 * Says what a valid answer looks like rather than only that this one is not, and rules out the
 * separators people reach for: "1,000" and "1 000" mean different amounts in different countries,
 * so they are refused rather than guessed at.
 */
export const AMOUNT_PROBLEM = 'Enter an amount in dollars, like 350 or 350.50 - digits only, no commas or spaces.';

export function isAmount(value: string): boolean {
  return AMOUNT.test(value.trim());
}

/**
 * Formats an amount the server sent for display, as "$1,570.00".
 *
 * Grouping only - the digits and the two decimal places are the server's, untouched. Anything that
 * is not a plain decimal string is shown exactly as it arrived, because a figure we cannot parse is
 * still a figure the server meant, and inventing one would be worse than showing it plainly.
 */
export function formatMoney(amount: string): string {
  const match = /^(-?)(\d+)(\.\d{2})?$/.exec(amount.trim());
  if (!match) return amount;
  const [, sign, whole, decimals] = match;
  const grouped = whole!.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${sign}$${grouped}${decimals ?? ''}`;
}

/**
 * Whether two amounts are the same money, as text: "350", "350.0" and "350.00" are one amount.
 *
 * Used to tell an answer the user changed from one they left at our suggestion. The difference
 * matters for provenance - sending our own estimate back as the user's figure would relabel it
 * "Your own figure" on every screen after, which is a claim nobody made.
 */
export function sameAmount(a: string, b: string): boolean {
  return normalise(a) === normalise(b);
}

function normalise(value: string): string {
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.trim());
  if (!match) return value.trim();
  const whole = match[1]!.replace(/^0+(?=\d)/, '');
  return `${whole}.${(match[2] ?? '').padEnd(2, '0')}`;
}
