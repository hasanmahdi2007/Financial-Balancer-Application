package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Map;
import java.util.Objects;

/**
 * The least a user should be left to live on each month, and why that figure.
 *
 * <p>The engine treats this as untouchable. Without it a short goal produces "cut Going out and fun
 * to $0, cut Eating out to $0" - arithmetically correct, and advice nobody follows.
 *
 * @param perCategory the same total split across the categories it protects, so the engine knows
 *     how far each individual line may be cut rather than only what the sum must not fall below
 * @param basis plain-language grounding, such as "typical for someone in Beirut who goes out
 *     regularly". A suggested figure with no stated basis reads as arbitrary, and a user who thinks
 *     a number is arbitrary either ignores it or replaces it at random.
 * @param userProvided true when the user answered the question themselves, in which case no
 *     clamp or multiplier was applied to their answer
 */
public record DiscretionaryFloor(
        Money monthly, Map<SpendCategory, Money> perCategory, String basis, boolean userProvided) {

    public DiscretionaryFloor {
        Objects.requireNonNull(monthly, "monthly");
        Objects.requireNonNull(basis, "basis");
        perCategory = Map.copyOf(perCategory);
        if (monthly.isNegative()) {
            throw new IllegalArgumentException("monthly must not be negative but was " + monthly);
        }
    }

    /**
     * The question to put to the user, pre-filled with this figure.
     *
     * <p>Always asked. The computed figure exists for the user who declines, skips onboarding, or
     * arrives with no lifestyle tier - it is a fallback, never the primary path, because the only
     * person who knows the least they would want to live on is the person living on it.
     */
    public SpendingQuestion asQuestion() {
        return SpendingQuestion.monthlyFloor(monthly, basis);
    }
}
