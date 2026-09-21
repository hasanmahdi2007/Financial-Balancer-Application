package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How far a stored figure has probably drifted, and what may be done about it.
 *
 * <pre>
 *   drift = annual_inflation x age_months / 12
 *   FRESH &lt; 5% &lt;= AGING &lt; 15% &lt;= STALE
 * </pre>
 *
 * <p>Derived rather than hardcoded, which is the whole point: the same code gives opposite answers
 * per country off one column. The US at 3%/yr keeps a figure FRESH for twenty months; Lebanon at
 * 17.3%/yr is AGING within four and STALE inside eleven. A hardcoded "figures expire after a year"
 * would be wrong in both directions at once.
 *
 * <p>Today's date is a parameter, never read from a clock. Domain code that reads the clock stops
 * being reproducible - the same inputs quietly produce a different answer tomorrow - and an
 * architecture rule enforces it.
 *
 * <p>The comparison is done in basis points and whole months so it is exact integer arithmetic. At a
 * threshold boundary a rounding mode would be the difference between FRESH and AGING, and nobody
 * would ever notice it was chosen by accident.
 */
public final class StalenessPolicy {

    private static final int AGING_AT_BASIS_POINTS = 500;
    private static final int STALE_AT_BASIS_POINTS = 1500;
    private static final int MONTHS_PER_YEAR = 12;

    private StalenessPolicy() {}

    /** Whole months between the figure's date and today; negative ages are treated as brand new. */
    public static int ageInMonths(LocalDate asOf, LocalDate today) {
        return (int) Math.max(0, ChronoUnit.MONTHS.between(asOf, today));
    }

    /** Drift in basis points, so that the threshold comparison never needs a rounding decision. */
    public static int driftBasisPoints(LocalDate asOf, LocalDate today, int annualInflationBasisPoints) {
        return annualInflationBasisPoints * ageInMonths(asOf, today) / MONTHS_PER_YEAR;
    }

    /** Drift as a percentage, for showing a person why their figure is being questioned. */
    public static BigDecimal driftPercent(
            LocalDate asOf, LocalDate today, int annualInflationBasisPoints) {
        return BigDecimal.valueOf(driftBasisPoints(asOf, today, annualInflationBasisPoints))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public static Staleness assess(LocalDate asOf, LocalDate today, int annualInflationBasisPoints) {
        int drift = driftBasisPoints(asOf, today, annualInflationBasisPoints);
        if (drift >= STALE_AT_BASIS_POINTS) {
            return Staleness.STALE;
        }
        return drift >= AGING_AT_BASIS_POINTS ? Staleness.AGING : Staleness.FRESH;
    }

    /**
     * What the figure would be if it had simply tracked inflation.
     *
     * <p>This is <strong>only ever a suggestion</strong>. It is never written back over the stored
     * figure, because inflating a number fabricates precision it never had: a curated estimate
     * multiplied by an inflation rate is still a curated estimate, just with more decimal places and
     * a misleading air of currency. It is offered as the pre-filled default in the override form, and
     * becomes {@link Confidence#USER_PROVIDED} only once the user confirms it.
     */
    public static Money inflationAdjusted(
            Money stored, LocalDate asOf, LocalDate today, int annualInflationBasisPoints) {
        int drift = driftBasisPoints(asOf, today, annualInflationBasisPoints);
        BigDecimal multiplier = BigDecimal.valueOf(10_000 + drift)
                .divide(BigDecimal.valueOf(10_000), 6, RoundingMode.HALF_UP);
        return new Money(stored.amount().multiply(multiplier));
    }
}
