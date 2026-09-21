package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * How long the considered balance would last at the current monthly shortfall.
 *
 * <p>One of only two ways a balance is allowed to influence a plan - the other being that it raises
 * a goal's saved amount and so shrinks what that goal needs each month. It is a display figure and
 * nothing more. The allocator receives a single monthly surplus and never sees a balance, which is
 * the narrowest and most valuable seam in the project.
 *
 * @param months whole months of cover, rounded down, because a partial month of rent is not a month
 *     of rent
 * @param indefinite true when there is no monthly shortfall to run down, in which case
 *     {@code months} carries no meaning
 */
public record Runway(int months, boolean indefinite) {

    public static final Runway INDEFINITE = new Runway(0, true);

    public Runway {
        if (months < 0) {
            throw new IllegalArgumentException("months must not be negative but was " + months);
        }
    }

    public static Runway of(ConsideredFunds funds, Money monthlyDeficit) {
        Objects.requireNonNull(funds, "funds");
        Objects.requireNonNull(monthlyDeficit, "monthlyDeficit");
        if (!monthlyDeficit.isPositive()) {
            return INDEFINITE;
        }
        // Clamped rather than converted exactly: a large balance against a shortfall of a few cents
        // runs to more months than an int can hold, and a display figure is not worth an
        // ArithmeticException. Anything past this is "indefinite" to a reader anyway.
        BigDecimal months = funds.consideredBalance()
                .amount()
                .divide(monthlyDeficit.amount(), 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(Integer.MAX_VALUE));
        return new Runway(months.intValue(), false);
    }
}
