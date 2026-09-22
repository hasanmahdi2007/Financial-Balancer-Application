package com.hasan.budget.planning.domain.decision;

/**
 * Where a price came from, and therefore how much it is worth believing.
 *
 * <p>Declaration order is precedence, as with the cost-of-living confidence tiers: the user's own
 * figure beats what their bank shows, which beats anything worked out from city prices. A price with
 * no basis attached is how "about $25" ends up presented as fact, so every estimate carries one.
 *
 * <p>Each constant carries its own wording for a person. Nothing here should ever reach the
 * interface as a constant name.
 */
public enum PriceBasis {

    /** The user typed the price themselves, so there is nothing left to estimate. */
    USER_STATED("The price you gave us", "You told us what this costs, so we use your figure."),

    /**
     * Averaged from the user's own card transactions. Available once transactions exist, and
     * arrived at by grouping on a stable merchant identifier rather than by asking a model, which
     * would return a confident number nobody can check.
     */
    OBSERVED(
            "What you actually pay",
            "Averaged from your own card payments at places like this, so it is your real price "
                    + "rather than a guess."),

    /** No transactions yet, so the figure is scaled from what things cost in the user's city. */
    ESTIMATED(
            "Our estimate",
            "We have no payments of yours to go on yet, so this is worked out from typical prices "
                    + "in your city.");

    private final String label;
    private final String meaning;

    PriceBasis(String label, String meaning) {
        this.label = label;
        this.meaning = meaning;
    }

    /** What to call this in the interface. */
    public String label() {
        return label;
    }

    /** One sentence saying what the label is actually claiming. */
    public String meaning() {
        return meaning;
    }
}
