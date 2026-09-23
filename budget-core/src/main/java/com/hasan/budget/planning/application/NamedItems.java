package com.hasan.budget.planning.application;

import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * How much of each category's figure the user has already put a name to.
 *
 * <p>One rule, in one place, because two things need it and they must agree: a cut candidate takes
 * the named part out of its category's headroom, and a rebalance line takes it out of that line's
 * amount. If those two ever disagreed, the same dollars would be offered twice in one screen - once
 * as "cut Subscriptions" and once as "cut your gym".
 *
 * <p>Only items that name part of a category count here. An item the user added on top of one is
 * money nothing else has counted, and subtracting it from its category would remove spending that was
 * never inside it.
 */
final class NamedItems {

    private NamedItems() {}

    static Map<SpendCategory, Money> withinCategories(List<UserLineItem> lineItems) {
        Map<SpendCategory, Money> named = new EnumMap<>(SpendCategory.class);
        for (UserLineItem item : lineItems) {
            if (!item.scope().isSubtractedInItsOwnRight()) {
                named.merge(item.parent(), item.monthlyAmount(), Money::plus);
            }
        }
        return named;
    }
}
