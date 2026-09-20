package com.hasan.budget.shared;

/**
 * How quickly an expense can actually be reduced. Deliberately independent of {@link Commitment}:
 * a streaming subscription is as recurring as rent but can be cancelled this afternoon, and
 * collapsing the two into one rank is what made an earlier model unable to express that.
 */
public enum CutSpeed {
    /** Can be reduced today. */
    IMMEDIATE,
    /** Can be trimmed by behaviour, but not eliminated. */
    PARTIAL,
    /** Reducible only over months, typically by changing provider or plan. */
    SLOW,
    /** Not reducible within the planning horizon at all. */
    NOT_SHORT_TERM
}
