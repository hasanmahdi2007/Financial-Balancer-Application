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
 * inhuman. The tier-to-floor figures belong in a lookup table, not in code.
 */
public enum LifestyleTier {
    /** Rarely goes out; most meals and entertainment happen at home. */
    HOMEBODY,
    /** Out occasionally - a meal or an outing every week or two. */
    OCCASIONAL,
    /** Out regularly; cafes and restaurants are a normal part of the week. */
    REGULAR,
    /** Out most days; nightlife, clubs and eating out are a large share of spending. */
    FREQUENT
}
