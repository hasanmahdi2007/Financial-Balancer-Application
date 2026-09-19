package com.hasan.budget.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Currency amount backed by BigDecimal. Never use double for money.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    private static final int SCALE = 2;

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    // Normalising scale here is what makes the record's generated equals() correct:
    // BigDecimal.equals is scale-sensitive, so 1.5 and 1.50 would otherwise differ.
    public Money {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(String value) {
        return new Money(new BigDecimal(value));
    }

    public static Money of(long value) {
        return new Money(BigDecimal.valueOf(value));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money times(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)));
    }

    /**
     * Splits an outstanding amount across a number of months, rounding up. Rounding down would
     * leave the goal short of its target on the final month ($1000 over 3 months at $333.33
     * reaches $999.99).
     */
    public Money spreadOver(int months) {
        if (months < 1) {
            throw new IllegalArgumentException("months must be >= 1 but was " + months);
        }
        return new Money(amount.divide(BigDecimal.valueOf(months), SCALE, RoundingMode.CEILING));
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return compareTo(other) >= 0 ? this : other;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
