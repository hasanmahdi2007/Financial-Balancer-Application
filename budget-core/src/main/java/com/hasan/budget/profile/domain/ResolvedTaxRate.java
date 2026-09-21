package com.hasan.budget.profile.domain;

import com.hasan.budget.costofliving.domain.Confidence;
import java.time.LocalDate;
import java.util.Objects;

/**
 * An effective tax rate, together with where it came from.
 *
 * <p>Resolved through the same ordered layer chain as a cost-of-living baseline, and labelled with
 * the same vocabulary, because it is the same kind of claim: a number the user is being asked to
 * trust, whose weight depends entirely on its source. Reusing {@link Confidence} rather than
 * inventing a parallel enum is what keeps "the user's own figure wins" one rule rather than two.
 *
 * <p><strong>Never {@code OFFICIAL}.</strong> Real tax systems are progressive, with brackets,
 * allowances and deductions; a single percentage is an approximation of one person's position in
 * that system, not a statutory truth. Presenting it as a statistic would be the sort of false
 * precision this product's confidence model exists to prevent, so the constructor refuses it.
 *
 * @param sourceName human-readable provenance, such as "Lebanon Law 144/2019 schedule, mid-band
 *     estimate"
 * @param asOf when the rate was set or published, not when it was read
 */
public record ResolvedTaxRate(Rate rate, Confidence confidence, String sourceName, LocalDate asOf) {

    public ResolvedTaxRate {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(asOf, "asOf");
        if (confidence != Confidence.USER_PROVIDED && confidence != Confidence.ESTIMATED) {
            throw new IllegalArgumentException(
                    "an effective rate approximates a progressive system, so it can only be the "
                            + "user's own figure or an estimate, never " + confidence);
        }
        if (rate.basisPoints() >= 10_000) {
            throw new IllegalArgumentException("a tax rate of " + rate + " would leave nothing");
        }
    }

    /** The seeded country figure, which every account shares until one of them overrides it. */
    public static ResolvedTaxRate estimatedForCountry(Rate rate, String sourceName, LocalDate asOf) {
        return new ResolvedTaxRate(rate, Confidence.ESTIMATED, sourceName, asOf);
    }

    /** A figure the user typed. Beats the seeded rate, and stays private to that account. */
    public static ResolvedTaxRate statedByUser(Rate rate, LocalDate asOf) {
        return new ResolvedTaxRate(rate, Confidence.USER_PROVIDED, "the rate you entered", asOf);
    }
}
