package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.SpendCategory;

/**
 * Whether a commitment the user named is already inside what its category shows, or sits on top of
 * it.
 *
 * <p>Both are things people genuinely want to do and they look identical in the data, which is what
 * makes leaving the choice implicit dangerous. Someone who names their gym under the subscriptions
 * they already track is labelling part of a total that is counted; someone who adds a season ticket
 * nobody has recorded is adding a new outflow. Subtracting the first would count it twice - the same
 * shape as the credit-card double count, arriving through a different door - and refusing to
 * subtract the second would lose it from the plan entirely.
 *
 * <p>The distinction is therefore asked rather than guessed, and each constant carries its own
 * wording so the question can be built from this table instead of a template that drifts. Nothing
 * here should ever reach a person as a constant name.
 */
public enum ItemScope {

    /**
     * Already inside the category's observed total. Naming it changes no arithmetic at all; it
     * exists so advice can say "cut your gym by $20" rather than "cut Subscriptions by $20".
     */
    ALREADY_COUNTED(
            "Part of what I already spend on %s",
            "we will not add it again - naming it just lets us tell you which thing to cut",
            false),

    /**
     * A commitment nothing else has counted, so it is subtracted in its own right. Its parent
     * category still decides how, which is why a custom item needs no arm of its own in the formula.
     */
    ON_TOP(
            "Extra, on top of my %s",
            "we will add it to what you spend, because nothing else has counted it yet",
            true);

    private final String labelTemplate;
    private final String means;
    private final boolean subtractedInItsOwnRight;

    ItemScope(String labelTemplate, String means, boolean subtractedInItsOwnRight) {
        this.labelTemplate = labelTemplate;
        this.means = means;
        this.subtractedInItsOwnRight = subtractedInItsOwnRight;
    }

    /**
     * The option as the user reads it, named for their own category rather than for this enum. Built
     * from the category's own label so that renaming a category cannot leave this wording stale.
     */
    public String labelFor(SpendCategory parent) {
        return labelTemplate.formatted(parent.label());
    }

    /** What choosing this actually does to their number, in their words. */
    public String means() {
        return means;
    }

    /**
     * Whether the amount reaches the arithmetic. Carried here as data rather than decided by a
     * second switch in the calculation, so the formula keeps its single switch over
     * {@link com.hasan.budget.shared.BaselinePolicy}.
     */
    public boolean isSubtractedInItsOwnRight() {
        return subtractedInItsOwnRight;
    }
}
