package com.hasan.budget.planning.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * Spending the plan may propose reducing, and how willing the user is to have it reduced.
 *
 * @param rigidity resolved by the caller from the category's default and the user's own override,
 *     so that cut ordering is a stated policy rather than whatever each caller happens to pass
 */
public record DiscretionarySpend(SpendCategory category, Money monthlyAmount, Rigidity rigidity) {

    public DiscretionarySpend {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(monthlyAmount, "monthlyAmount");
        Objects.requireNonNull(rigidity, "rigidity");
        if (monthlyAmount.isNegative()) {
            throw new IllegalArgumentException("monthlyAmount must not be negative but was " + monthlyAmount);
        }
    }

    /** Uses the category's default rigidity, for callers with no user override to apply. */
    public static DiscretionarySpend of(SpendCategory category, Money monthlyAmount) {
        Objects.requireNonNull(category, "category");
        return new DiscretionarySpend(category, monthlyAmount, category.defaultRigidity());
    }

    /** Whether the engine may propose a numeric cut here, as opposed to only a qualitative hint. */
    public boolean isCuttable() {
        return rigidity != Rigidity.LOCKED && category.autoSuggestCuts();
    }
}
