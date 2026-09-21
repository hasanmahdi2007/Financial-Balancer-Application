package com.hasan.budget.profile.domain;

/**
 * How much of the user's life happens outside the house.
 *
 * <p>This does two distinct jobs. Before any bank data exists it seeds an expected discretionary
 * profile, so the app is useful on first run rather than blank. Once real transactions arrive it
 * becomes a reality check: "you said you rarely go out, but cafe and nightlife spending is $600 a
 * month" is one of the more genuinely useful things the product can say.
 *
 * <p>It also seeds the discretionary floor. Without one the engine will happily recommend cutting
 * all entertainment to zero, which is advice nobody follows and which makes the product feel
 * inhuman. The tier-to-floor figures belong in a lookup table, not in code, which is why nothing
 * numeric lives here - only the wording needed to ask about a tier and to explain a suggestion
 * derived from it.
 */
public enum LifestyleTier {
    /** Rarely goes out; most meals and entertainment happen at home. */
    HOMEBODY("Mostly at home", "who rarely goes out"),
    /** Out occasionally - a meal or an outing every week or two. */
    OCCASIONAL("Out now and then", "who goes out now and then"),
    /** Out regularly; cafes and restaurants are a normal part of the week. */
    REGULAR("Out regularly", "who goes out regularly"),
    /** Out most days; nightlife, clubs and eating out are a large share of spending. */
    FREQUENT("Out most days", "who is out most days");

    private final String label;
    private final String describesSomeone;

    LifestyleTier(String label, String describesSomeone) {
        this.label = label;
        this.describesSomeone = describesSomeone;
    }

    /** What to call this tier when offering it as a choice. Never show a constant name to a user. */
    public String label() {
        return label;
    }

    /**
     * A relative clause that completes "typical for someone in Beirut ...", so that a suggested
     * figure states what it is based on. A pre-filled number with no stated grounding reads as
     * arbitrary, and a user who thinks a number is arbitrary changes it at random or ignores it.
     */
    public String describesSomeone() {
        return describesSomeone;
    }
}
