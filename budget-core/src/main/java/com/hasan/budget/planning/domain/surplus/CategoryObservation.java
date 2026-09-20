package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * What the user actually spent in a category this month, and what they are expected to.
 *
 * <p>{@code effectiveBaseline} is already resolved before it arrives: it is the user's own figure
 * where they set one, and the localised baseline otherwise. This record deliberately does not carry
 * the confidence or the source, so the arithmetic cannot branch on provenance.
 *
 * @param effectiveBaseline null for categories that are never measured against a baseline
 */
public record CategoryObservation(SpendCategory category, Money actual, Money effectiveBaseline) {

    public CategoryObservation {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(actual, "actual");
        if (actual.isNegative()) {
            throw new IllegalArgumentException("actual must not be negative but was " + actual);
        }
    }

    public static CategoryObservation withoutBaseline(SpendCategory category, Money actual) {
        return new CategoryObservation(category, actual, null);
    }
}
