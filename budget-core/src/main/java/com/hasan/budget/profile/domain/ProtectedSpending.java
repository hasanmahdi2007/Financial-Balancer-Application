package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.SpendCategory;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The spending the discretionary floor exists to protect, derived from the taxonomy rather than
 * listed by hand.
 *
 * <p>A floor only means anything where the engine would otherwise propose a cut, so the set is
 * exactly the categories that are discretionary <em>and</em> open to an automatic suggestion.
 * {@code OTHER} is discretionary but is never proposed as a cut - "reduce Everything else by $150"
 * is advice nobody can act on - so there is nothing there to protect.
 *
 * <p>Deriving the list is what keeps the floor question, the floor policy table and the engine in
 * agreement. Writing the three constants out in any one of those places would mean adding a
 * category silently stopped protecting it in the other two.
 *
 * <p>Ordered by cut order, so the user reads them in the order the engine would take them.
 */
public final class ProtectedSpending {

    private ProtectedSpending() {}

    public static List<SpendCategory> categories() {
        return Arrays.stream(SpendCategory.values())
                .filter(category -> category.baselinePolicy() == BaselinePolicy.DISCRETIONARY)
                .filter(SpendCategory::autoSuggestCuts)
                .sorted(Comparator.comparingInt(SpendCategory::cutOrder))
                .toList();
    }
}
