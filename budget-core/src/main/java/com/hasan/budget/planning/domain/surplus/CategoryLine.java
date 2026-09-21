package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
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
 * @param rigidity how willing the user is to have this reduced. Carried on the line because whoever
 *     proposes a cut reads the breakdown, and their answer would otherwise be lost: a gym the user
 *     marked {@code LOCKED} would fall back to its category's default, which is cuttable, and the
 *     engine would propose cutting the one thing they said never to touch. For a whole category
 *     this is that category's default; for a named item it is the user's own choice.
 */
public record CategoryLine(
        SpendCategory category,
        String lineItemId,
        String label,
        Money actual,
        Money baseline,
        Money counted,
        BaselinePolicy policy,
        Rigidity rigidity) {

    public CategoryLine {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(counted, "counted");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(rigidity, "rigidity");
    }

    /** True when the user spent more than the local expectation, so the excess is controllable. */
    public boolean isAboveBaseline() {
        return baseline != null && actual.compareTo(baseline) > 0;
    }

    /**
     * Whether a numeric cut may be proposed against this line at all. Mirrors the same two
     * conditions the allocator applies, so a caller assembling cut candidates cannot accidentally
     * offer up a locked line or a category the engine refuses to name.
     */
    public boolean isCuttable() {
        return rigidity != Rigidity.LOCKED && category.autoSuggestCuts();
    }
}
