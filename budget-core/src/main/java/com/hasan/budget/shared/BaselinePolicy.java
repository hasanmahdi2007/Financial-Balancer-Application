package com.hasan.budget.shared;

/**
 * How a category enters the surplus formula. This is the only axis the surplus calculation switches
 * on, which is what keeps that calculation free of per-category branching.
 */
public enum BaselinePolicy {
    /**
     * Subtracted at the full observed amount and never capped against a baseline. You cannot cap a
     * lease mid-month, so pretending otherwise would overstate the surplus.
     */
    TAKE_AS_IS,
    /**
     * Subtracted as {@code min(actual, localBaseline)}. Spending above the local baseline is treated
     * as controllable rather than as an unavoidable cost.
     */
    CAP_AT_BASELINE,
    /** Covered by the discretionary floor rather than category by category. */
    DISCRETIONARY
}
