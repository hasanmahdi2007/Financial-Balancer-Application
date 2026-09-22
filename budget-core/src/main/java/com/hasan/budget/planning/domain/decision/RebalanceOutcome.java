package com.hasan.budget.planning.domain.decision;

/**
 * How far a requested increase got.
 *
 * <p>Four values rather than a boolean, because "it did not fully work" has three genuinely different
 * remedies: find the rest somewhere else, unlock the thing you asked to raise, or change the plan's
 * shape. Collapsing them would leave the interface guessing which sentence to show.
 */
public enum RebalanceOutcome {

    /** The whole increase was found in other lines. Nothing is owed and no floor was reached. */
    ABSORBED(
            "Done - here is what gives",
            "We found the whole increase in your other spending, and nothing dropped below what your "
                    + "city says it costs."),

    /** Some of the increase was found; floors and locks stopped the rest. */
    PARTIALLY_ABSORBED(
            "We found part of it",
            "Your other spending is already as low as it can honestly go, so only part of the increase "
                    + "is covered."),

    /** Nothing could give at all. */
    INFEASIBLE(
            "We could not find this anywhere",
            "Everything else is already at its lowest, or is something you told us cannot change."),

    /**
     * The user asked to raise a line they had marked as unchangeable. Rebalancing never touches such a
     * line in either direction, so the answer is about the mark rather than about the money.
     */
    TARGET_IS_LOCKED(
            "You told us this one cannot change",
            "You marked this as unchangeable, so we leave it alone in both directions. Change that "
                    + "first if you want to raise it.");

    private final String label;
    private final String meaning;

    RebalanceOutcome(String label, String meaning) {
        this.label = label;
        this.meaning = meaning;
    }

    /** What to call this in the interface. */
    public String label() {
        return label;
    }

    /** One sentence saying why, without an internal term in it. */
    public String meaning() {
        return meaning;
    }
}
