package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A baseline figure together with everything needed to judge it.
 *
 * <p>The provider never returns a bare {@link Money}. A bare number loses where it came from, and
 * once that is gone the UI cannot honestly tell the user whether they are looking at a government
 * statistic or a guess - which is the product's main claim on their trust.
 *
 * <p>Note what does <em>not</em> happen next: the planning math receives only {@code amount}.
 * Confidence travels to the view. If the arithmetic could see it, someone would eventually write
 * {@code if (confidence == ESTIMATED)} inside the formula, and the branch sprawl would begin.
 *
 * @param sourceName human-readable provenance, e.g. "BEA RPP 2023 x BLS CEX 2024"
 * @param asOf when the underlying data was published, not when it was fetched
 */
public record ResolvedBaseline(
        Money amount, Confidence confidence, String sourceName, LocalDate asOf, Staleness staleness) {

    public ResolvedBaseline {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(staleness, "staleness");
    }
}
