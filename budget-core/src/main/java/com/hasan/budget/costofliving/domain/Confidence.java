package com.hasan.budget.costofliving.domain;

/**
 * How much weight a figure deserves, and where it came from.
 *
 * <p>Every baseline the app shows carries one of these, because the difference between a government
 * statistic and a guess is the difference between advice and noise. Declaration order is precedence:
 * the resolver consults sources in this order and the first hit wins.
 */
public enum Confidence {
    /** The user's own figure. Beats everything, permanently - they know what they actually pay. */
    USER_PROVIDED,
    /** Derived from official statistics: BLS CEX localised by BEA Regional Price Parities. */
    OFFICIAL,
    /** A user submission that passed validation and was approved as a city default. */
    CONTRIBUTED,
    /** A curated snapshot with no official source, such as the Lebanese cities. */
    CROWDSOURCED,
    /** Country-level fallback where no city figure exists. Always labelled as such in the UI. */
    ESTIMATED
}
