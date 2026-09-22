package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * One line moving, named as the user named it.
 *
 * <p>Both ends are carried rather than only the difference, because "your eating out goes from $300
 * to $250" is checkable by the person reading it and a bare "-$50" is not.
 */
public record Adjustment(String lineItemId, String label, SpendCategory category, Money from, Money to) {

    public Adjustment {
        Objects.requireNonNull(lineItemId, "lineItemId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    /** Positive where the line rose, negative where it gave. */
    public Money change() {
        return to.minus(from);
    }
}
