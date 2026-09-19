package com.hasan.budget.planning.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * @param flexibilityRank lower ranks are cut first when a goal needs funding
 */
public record DiscretionarySpend(SpendCategory category, Money monthlyAmount, int flexibilityRank) {

    public DiscretionarySpend {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(monthlyAmount, "monthlyAmount");
        if (monthlyAmount.isNegative()) {
            throw new IllegalArgumentException("monthlyAmount must not be negative but was " + monthlyAmount);
        }
    }
}
