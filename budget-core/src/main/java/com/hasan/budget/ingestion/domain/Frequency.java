package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How often a detected stream repeats, and what that is worth in a month.
 *
 * <p>A plan is monthly, so a weekly payment has to be converted before it can sit beside rent. The
 * rate is attached to the constant rather than computed in a switch somewhere, so a provider that
 * reports a new cadence is one row here.
 */
public enum Frequency {
    WEEKLY(52),
    BIWEEKLY(26),
    SEMI_MONTHLY(24),
    MONTHLY(12),
    ANNUALLY(1),
    /** The provider saw a repeat it could not put a cadence on. Treated as monthly. */
    UNKNOWN(12);

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    private final int timesPerYear;

    Frequency(int timesPerYear) {
        this.timesPerYear = timesPerYear;
    }

    public int timesPerYear() {
        return timesPerYear;
    }

    /**
     * What one occurrence of this size costs per month.
     *
     * <p>Multiplied and divided in one unrounded step and brought to cents once at the end: doing it
     * as {@code Money} arithmetic would round at every stage, and the roundings compound. The
     * direction is nearest, because a monthly equivalent is an estimate and an estimate has no safe
     * direction - rounding it up by habit would quietly overstate every commitment a user has.
     */
    public Money monthlyEquivalent(Money perOccurrence) {
        BigDecimal monthly = perOccurrence
                .amount()
                .multiply(BigDecimal.valueOf(timesPerYear))
                .divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
        // Already at the scale Money keeps, so wrapping it rounds nothing a second time.
        return new Money(monthly);
    }

    /** Falls back to {@link #UNKNOWN} rather than failing, so a new provider value cannot stop a sync. */
    public static Frequency parse(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        for (Frequency frequency : values()) {
            if (frequency.name().equalsIgnoreCase(value)) {
                return frequency;
            }
        }
        return UNKNOWN;
    }
}
