package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.CountryCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A country we hold data for, and the one number that decides how fast its figures rot.
 *
 * <p>The inflation rate is held as <strong>basis points</strong> - hundredths of a percent, so 17.3%
 * is 1730 - rather than as a decimal. Two reasons, both deliberate. Integer arithmetic makes the
 * drift comparison exact, with no rounding mode to argue about at a threshold boundary. And a
 * {@code BigDecimal} field in a domain package is exactly what the architecture rules forbid: the
 * only decimal type allowed to cross this layer is {@link com.hasan.budget.shared.Money}, because
 * scale-sensitive equality has already caused real bugs here.
 *
 * <p>The stored column is still {@code annual_inflation_pct}, as the design record names it, and
 * {@link #fromPercent} is the single place the two representations meet.
 *
 * @param listed whether the signup dropdown may offer this country. A country with no data must
 *     never appear, because choosing it is a dead end the user cannot back out of.
 * @param dataNote what a person should know before trusting these figures, such as Lebanon being
 *     dollar-priced in practice. Shown in the interface, not kept for developers.
 */
public record CountryProfile(
        CountryCode code,
        String name,
        String currency,
        int annualInflationBasisPoints,
        LocalDate inflationAsOf,
        boolean listed,
        String dataNote) {

    public CountryProfile {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(inflationAsOf, "inflationAsOf");
        Objects.requireNonNull(dataNote, "dataNote");
        if (annualInflationBasisPoints < 0) {
            throw new IllegalArgumentException(
                    "annual inflation must not be negative but was " + annualInflationBasisPoints);
        }
    }

    /** Builds from the stored percentage column, which is where the two representations meet. */
    public static CountryProfile fromPercent(
            CountryCode code,
            String name,
            String currency,
            BigDecimal annualInflationPct,
            LocalDate inflationAsOf,
            boolean listed,
            String dataNote) {
        // Inlined rather than held as a constant: a BigDecimal field in a domain class is what the
        // architecture rule forbids, and the rule is right - Money is the only decimal type allowed
        // to live here, because every other one is a place scale-sensitive equality can creep back in.
        int basisPoints = annualInflationPct
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        return new CountryProfile(
                code, name, currency, basisPoints, inflationAsOf, listed, dataNote);
    }
}
