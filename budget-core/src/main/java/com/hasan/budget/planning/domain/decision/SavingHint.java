package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * What the user could do about a line the engine may not cut, named as their own thing.
 *
 * <p>Deliberately carries no {@link com.hasan.budget.shared.Money}. That absence is the point: a hint
 * is the one output of this engine that is not a number, and the moment it were one, a caller would
 * add it to a total and the plan would count a saving nobody has made yet. Turning hints on must not
 * move a single figure in the plan.
 */
public record SavingHint(String lineItemId, String label, SpendCategory category, String lever) {

    public SavingHint {
        Objects.requireNonNull(lineItemId, "lineItemId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(lever, "lever");
    }

    /** The hint for one line of the plan, taking its wording from that category's lever row. */
    public static SavingHint forLine(BudgetLine line) {
        Objects.requireNonNull(line, "line");
        return new SavingHint(
                line.id(), line.label(), line.category(), SavingLever.forCategory(line.category()).lever());
    }
}
