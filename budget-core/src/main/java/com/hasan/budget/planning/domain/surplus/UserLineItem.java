package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * A commitment the user named themselves, such as a season ticket at $300 a month.
 *
 * <p>These count in the surplus like anything else. The point of the design is that they need no new
 * arm in the formula: the {@code parent} category decides how the item is treated, so an item
 * parented to {@code ENTERTAINMENT} is discretionary while one parented to {@code SUBSCRIPTIONS} is
 * taken as-is. Adding a user item must never require touching the calculation.
 *
 * @param label shown back to the user, so advice can say "cut your match tickets by $50" rather than
 *     the much less actionable "cut Entertainment by $50"
 * @param rigidity the user's own judgement, defaulted from the parent category and overridable per
 *     item; carried faithfully here and acted on when cuts are proposed
 */
public record UserLineItem(
        String id, String label, SpendCategory parent, Money monthlyAmount, Rigidity rigidity) {

    public UserLineItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(monthlyAmount, "monthlyAmount");
        Objects.requireNonNull(rigidity, "rigidity");
        if (monthlyAmount.isNegative()) {
            throw new IllegalArgumentException("monthlyAmount must not be negative but was " + monthlyAmount);
        }
    }

    /** Uses the parent category's default rigidity, for callers with no user override to apply. */
    public static UserLineItem of(String id, String label, SpendCategory parent, Money monthlyAmount) {
        Objects.requireNonNull(parent, "parent");
        return new UserLineItem(id, label, parent, monthlyAmount, parent.defaultRigidity());
    }
}
