package com.hasan.budget.planning.domain.decision;

/**
 * The answer to "can I afford this".
 *
 * <p>Three values rather than two, because a straight yes-or-no against the daily rate would call a
 * meal twenty cents over it unaffordable, and a tool that cries wolf at twenty cents stops being
 * read. The middle value is the honest one for most real purchases.
 *
 * <p>Each constant carries its own wording. Nothing here reaches a person as a constant name.
 */
public enum SpendVerdict {

    /** At or under the daily rate, so it needs no adjustment afterwards at all. */
    COMFORTABLE(
            "You can afford this",
            "It is within what you have left to spend per day for the rest of the month."),

    /**
     * A little over the daily rate. Genuinely fine, provided the following days run slightly lower,
     * which is what the catch-up plan spells out.
     */
    SUSTAINABLE(
            "This works if you ease off afterwards",
            "It is a little above your daily pace, and trimming the next few days covers it."),

    /** Far enough over the daily rate that the rest of the month cannot quietly absorb it. */
    OVER_BUDGET(
            "This is more than you have room for",
            "Buying this puts you past what is left for the rest of the month.");

    private final String label;
    private final String meaning;

    SpendVerdict(String label, String meaning) {
        this.label = label;
        this.meaning = meaning;
    }

    /** What to call this in the interface. */
    public String label() {
        return label;
    }

    /** One sentence saying why. */
    public String meaning() {
        return meaning;
    }
}
