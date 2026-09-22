package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;

/**
 * The three pieces of money arithmetic this package needs beyond what {@link Money} offers, and the
 * reasoning for each rounding direction.
 *
 * <p>They live together rather than being inlined at every call site because the direction is the
 * part that is easy to get wrong and impossible to see in a one-line expression. A daily rate
 * rounded the wrong way either tells the user they can spend more than they have or has them
 * following a plan that overshoots their allowance, and neither failure announces itself.
 *
 * <p>{@code Money} deliberately exposes only {@link Money#spreadOver(int)}, which rounds up. That is
 * the right default for the case it was written for - splitting a savings target across months,
 * where rounding down leaves the goal a cent short on the final month - so the two directions here
 * are both expressed in terms of it rather than by reaching for {@code BigDecimal} again.
 */
final class Amounts {

    private Amounts() {}

    /**
     * A whole-percent share of an amount, rounded up. Used for band prices and tolerance limits,
     * where up is the cautious direction: a slightly dearer estimate and a slightly wider tolerance
     * both err toward telling the user something costs more than it might, never less.
     */
    static Money percentOf(Money amount, int percent) {
        if (percent < 0) {
            throw new IllegalArgumentException("percent must not be negative but was " + percent);
        }
        return amount.times(percent).spreadOver(HUNDRED);
    }

    /**
     * An amount split across periods, rounded up, so the periods together cover at least the whole
     * amount. Used for a reduction the user has to make: cutting a shade more per day than strictly
     * needed errs toward staying on track.
     */
    static Money perPeriodAtLeast(Money total, int periods) {
        return total.spreadOver(periods);
    }

    /**
     * An amount split across periods, rounded down, so the periods together never exceed the whole
     * amount. Used for a rate the user is allowed to spend at: rounding a spending allowance up by
     * a cent a day is how a plan that claims to land exactly on the allowance quietly overshoots it.
     *
     * <p>Expressed as the ceiling of the negated amount, which is the same value, because rounding
     * away from zero on a negative number rounds down on the positive one.
     */
    static Money perPeriodAtMost(Money total, int periods) {
        if (total.isNegative()) {
            throw new IllegalArgumentException("total must not be negative but was " + total);
        }
        Money roundedAwayFromZero = Money.ZERO.minus(total).spreadOver(periods);
        return Money.ZERO.minus(roundedAwayFromZero);
    }

    private static final int HUNDRED = 100;
}
