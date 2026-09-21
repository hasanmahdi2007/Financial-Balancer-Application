package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A proportion, held as whole basis points.
 *
 * <p>Basis points rather than a decimal fraction for the same reason {@link Money} wraps
 * {@code BigDecimal}: a rate is compared, stored and seeded from a table, and a binary fraction
 * cannot represent 20% exactly, so {@code 0.2 * 3} stops equalling {@code 0.6}. An {@code int} of
 * hundredths of a percent is exact, sorts correctly, and survives a round trip through a database
 * column without a scale argument. The domain's guardrail against raw decimals therefore costs
 * nothing here - it is the representation we would have picked anyway.
 *
 * <p>Deliberately not capped at 100%. The obligation multipliers that adjust the discretionary floor
 * run to 115%, and a rate that could not express that would need a second type beside it.
 */
public record Rate(int basisPoints) implements Comparable<Rate> {

    // Held as an int, not a BigDecimal constant: a domain class may not carry a raw decimal field,
    // and the guardrail is right to say so even for something that is never money.
    private static final int ONE_HUNDRED_PERCENT_BP = 10_000;

    public static final Rate ZERO = new Rate(0);

    public Rate {
        if (basisPoints < 0) {
            throw new IllegalArgumentException("a rate must not be negative but was " + basisPoints + "bp");
        }
    }

    /** {@code Rate.ofPercent("17.3")} is 1730 basis points. Fractions below 0.01% are rejected. */
    public static Rate ofPercent(String percent) {
        BigDecimal points = new BigDecimal(percent).movePointRight(2);
        try {
            return new Rate(points.intValueExact());
        } catch (ArithmeticException finerThanABasisPoint) {
            throw new IllegalArgumentException(
                    percent + "% is finer than a basis point, which this rate cannot hold exactly",
                    finerThanABasisPoint);
        }
    }

    /** The share of an amount this rate describes, rounded to the cent by {@link Money}. */
    public Money applyTo(Money amount) {
        return new Money(amount.amount()
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(oneHundredPercent(), 10, RoundingMode.HALF_UP));
    }

    /**
     * The pre-tax figure a net amount implies, for explaining the gross-to-net gap to a user whose
     * income already arrives taxed. Never used to subtract anything - that is the double
     * subtraction this product exists to avoid.
     */
    public Money grossUpFrom(Money net) {
        if (basisPoints >= ONE_HUNDRED_PERCENT_BP) {
            throw new IllegalArgumentException("cannot gross up at " + this + "; nothing would be left");
        }
        return new Money(net.amount()
                .multiply(oneHundredPercent())
                .divide(oneHundredPercent().subtract(BigDecimal.valueOf(basisPoints)), 10, RoundingMode.HALF_UP));
    }

    /** The share one amount is of another, for questions like "how much of your income is rent?". */
    public static Rate ratioOf(Money part, Money whole) {
        if (!whole.isPositive()) {
            throw new IllegalArgumentException("cannot take a ratio of " + whole);
        }
        return new Rate(part.amount()
                .multiply(oneHundredPercent())
                .divide(whole.amount(), 0, RoundingMode.HALF_UP)
                .intValueExact());
    }

    private static BigDecimal oneHundredPercent() {
        return BigDecimal.valueOf(ONE_HUNDRED_PERCENT_BP);
    }

    @Override
    public int compareTo(Rate other) {
        return Integer.compare(basisPoints, other.basisPoints);
    }

    @Override
    public String toString() {
        return BigDecimal.valueOf(basisPoints).movePointLeft(2).stripTrailingZeros().toPlainString() + "%";
    }
}
