package com.hasan.budget.costofliving.domain;

/**
 * How much weight a figure deserves, and where it came from.
 *
 * <p>Every baseline the app shows carries one of these, because the difference between a government
 * statistic and a guess is the difference between advice and noise. Declaration order is precedence:
 * the resolver consults sources in this order and the first hit wins.
 *
 * <p>Each constant carries its own wording for a person, beside the behaviour, for the same reason
 * {@code SpendCategory} does. {@code CROWDSOURCED} is an internal word; "researched by us, not an
 * official statistic" is what a user needs to read, and deriving it from here rather than from a
 * template in the interface means a new tier cannot ship without one.
 */
public enum Confidence {
    /** The user's own figure. Beats everything, permanently - they know what they actually pay. */
    USER_PROVIDED("Your own figure", "You told us this, so we use it and nothing overrides it."),
    /** Derived from official statistics: BLS CEX localised by BEA Regional Price Parities. */
    OFFICIAL(
            "Official statistics",
            "Worked out from published government figures for your area."),
    /** A user submission that passed validation and was approved as a city default. */
    CONTRIBUTED(
            "Shared by someone who lives there",
            "Submitted by another user, checked against their own bank transactions and approved by "
                    + "hand."),
    /** A curated snapshot with no official source, such as the Lebanese cities. */
    CROWDSOURCED(
            "Researched by us",
            "We gathered this from listings and local prices. It is a careful estimate, not an "
                    + "official statistic."),
    /** Country-level fallback where no city figure exists. Always labelled as such in the UI. */
    ESTIMATED(
            "A country-wide estimate",
            "We have nothing specific to your city yet, so this is a rough middle figure for your "
                    + "country.");

    private final String label;
    private final String meaning;

    Confidence(String label, String meaning) {
        this.label = label;
        this.meaning = meaning;
    }

    /** What to call this in the interface. Never show the constant name to a user. */
    public String label() {
        return label;
    }

    /** One sentence explaining what the label is actually claiming. */
    public String meaning() {
        return meaning;
    }
}
