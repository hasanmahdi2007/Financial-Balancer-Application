package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * One line of the breakdown: what was observed, what was expected, and what the formula actually
 * used.
 *
 * <p>{@code counted} is the figure that reached the arithmetic, and it is shown separately from
 * {@code actual} on purpose. When rent is above baseline the two differ, and being able to point at
 * that difference is what lets the plan say "this is what pushed your goal out".
 *
 * @param lineItemId set when this line is a user-named item rather than a whole category
 */
public record CategoryLine(
        SpendCategory category,
        String lineItemId,
        String label,
        Money actual,
        Money baseline,
        Money counted,
        BaselinePolicy policy) {

    public CategoryLine {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(counted, "counted");
        Objects.requireNonNull(policy, "policy");
    }

    /** True when the user spent more than the local expectation, so the excess is controllable. */
    public boolean isAboveBaseline() {
        return baseline != null && actual.compareTo(baseline) > 0;
    }
}
