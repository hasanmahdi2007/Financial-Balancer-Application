package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A figure the user typed for themselves.
 *
 * <p>It applies to their plan the moment they save it, beats every other source permanently, and is
 * visible to nobody else. If the app estimates 400 dollars of groceries and the user knows theirs is
 * 250, 250 wins - including over official government statistics, because the user is the only source
 * with access to their actual receipts.
 *
 * <p>Sharing is a separate, later, opt-in step: see {@link Contribution}. Nothing here leaves the
 * account on its own.
 */
public record UserOverride(
        String userId,
        SpendCategory category,
        Money amount,
        LocalDate setAt,
        Corroboration corroboration) {

    public UserOverride {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(setAt, "setAt");
        Objects.requireNonNull(corroboration, "corroboration");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("a figure must not be negative but was " + amount);
        }
    }

    public ResolvedBaseline asBaseline() {
        return new ResolvedBaseline(
                amount, Confidence.USER_PROVIDED, "your own figure", setAt, Staleness.FRESH);
    }
}
