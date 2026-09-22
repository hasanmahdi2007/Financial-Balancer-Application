package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount the user may type either way round.
 *
 * <p>People think about budgets in both currencies: "give me $700 more for going out" and "make it
 * 10% of what I have" are the same instruction. Holding two fields would let them drift, and
 * converting at the edge in each caller would let two callers convert differently. So there is one
 * stored number, {@link #amount()}, and the percentage is a view of it against the pool it is a share
 * of.
 *
 * <p>Whole percentages, because that is what a control a person operates actually offers, and because
 * a fractional percentage field would put a raw decimal back into the domain that {@link Money} exists
 * to keep out.
 */
public record Share(Money amount) {

    private static final int PERCENT = 100;

    public Share {
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("amount must not be negative but was " + amount);
        }
    }

    /** The dollars view: the user typed a figure. */
    public static Share ofAmount(Money amount) {
        return new Share(amount);
    }

    /** The percentage view: the user moved a control, against the pool the share comes out of. */
    public static Share ofPercent(int percent, Money pool) {
        Objects.requireNonNull(pool, "pool");
        if (percent < 0) {
            throw new IllegalArgumentException("percent must not be negative but was " + percent);
        }
        return new Share(Amounts.fractionOf(pool, percent, 100));
    }

    /**
     * This share read back as a whole percentage of the same pool, for putting the control back where
     * the user left it. Rounded to the nearest whole percent, so it is a display value and never the
     * one the arithmetic uses - that is always {@link #amount()}, which is why the rounding here can
     * be lossy without costing anyone a cent.
     *
     * <p>The one place in this package that reaches for {@link java.math.BigDecimal}, because dividing
     * one amount by another is not something {@link Money} offers and should not be: the result is a
     * ratio rather than money. It stays a local, never a field, which is the line the architecture rule
     * actually draws.
     */
    public int asPercentOf(Money pool) {
        Objects.requireNonNull(pool, "pool");
        if (pool.isZero()) {
            return 0;
        }
        return amount.times(PERCENT)
                .amount()
                .divide(pool.amount(), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }
}
