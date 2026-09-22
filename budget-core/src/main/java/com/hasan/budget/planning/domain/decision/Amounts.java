package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The two pieces of money arithmetic this package needs beyond what {@link Money} offers.
 *
 * <p><strong>The rule both of them exist to enforce: round once, at the end, and by as little as the
 * answer allows.</strong> Every intermediate step is carried at full precision and only the figure
 * actually reported to the user is brought back to the cent. Chaining {@code Money} operations cannot
 * do that, because {@code Money} normalises to two decimal places in its constructor - so a
 * three-step calculation rounds three times and the errors compound in whatever direction each step
 * happened to choose. That is why this is the one class in the package that unwraps a {@code Money}
 * to a {@link BigDecimal}: keeping the unrounded arithmetic in one small file is what stops it
 * leaking into the engine, and every method here hands back a {@code Money} immediately.
 *
 * <p>Direction is chosen once, per figure, for a stated reason. {@link #fractionOf} rounds to the
 * nearest cent because a price estimate or a share has no safe direction - nearest is simply the
 * least wrong answer. {@link #perDayAtMost} rounds down because a rate the user is invited to spend
 * at must never exceed what they actually have.
 *
 * <p>Note what is <em>not</em> here: anything that rounds a reduction up. {@link Money#spreadOver}
 * does that, and is right for what it was written for - splitting a savings target across months,
 * where rounding down leaves the goal a cent short on the final month. Here the equivalent falls out
 * for free: the reduction a user must make is the difference between two rates that were each already
 * rounded once, and flooring a rate <em>is</em> rounding its reduction up. One rounding, not two.
 */
final class Amounts {

    private static final int CENTS = 2;

    private Amounts() {}

    /**
     * {@code amount x numerator / denominator}, rounded to the nearest cent exactly once.
     *
     * <p>Both factors are taken together rather than applied in sequence, which is the whole point.
     * A band price is a percentage of a typical meal, and a typical meal is a fraction of a monthly
     * budget; computing it as two steps rounds twice and, where both steps round the same way, the
     * error compounds into a price a cent or more off what it should be. Expressed as one fraction it
     * rounds once and cannot drift.
     */
    static Money fractionOf(Money amount, int numerator, int denominator) {
        if (numerator < 0) {
            throw new IllegalArgumentException("numerator must not be negative but was " + numerator);
        }
        if (denominator < 1) {
            throw new IllegalArgumentException("denominator must be >= 1 but was " + denominator);
        }
        return new Money(amount.amount()
                .multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), CENTS, RoundingMode.HALF_UP));
    }

    /**
     * An amount split across days, rounded down, so the days together never exceed the whole amount.
     *
     * <p>Down rather than nearest, because this is a rate the user is told they may spend at. At
     * $400 over 21 days the exact figure is $19.047619, and reporting $19.05 invites them to spend
     * $400.05 - which is the same problem the plan was supposed to solve, one cent at a time,
     * twenty-one times over.
     */
    static Money perDayAtMost(Money total, int days) {
        if (total.isNegative()) {
            throw new IllegalArgumentException("total must not be negative but was " + total);
        }
        if (days < 1) {
            throw new IllegalArgumentException("days must be >= 1 but was " + days);
        }
        return new Money(total.amount().divide(BigDecimal.valueOf(days), CENTS, RoundingMode.FLOOR));
    }
}
