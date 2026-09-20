package com.hasan.budget.costofliving.domain;

/**
 * How far a figure has probably drifted since it was recorded.
 *
 * <p>Derived rather than hardcoded: {@code drift = annual_inflation x age_months / 12}. The same
 * thresholds behave very differently per country, which is the point - the US at about 3% a year
 * keeps a figure FRESH for roughly twenty months, while Lebanon at 17.3% reaches STALE in about ten.
 * One column drives both.
 */
public enum Staleness {
    /** Drift under 5%. */
    FRESH,
    /** Drift between 5% and 15%. Worth showing, not worth blocking on. */
    AGING,
    /** Drift of 15% or more. Prompt the user to confirm or replace the figure. */
    STALE
}
