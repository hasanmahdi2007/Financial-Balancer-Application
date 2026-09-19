package com.hasan.budget.planning.domain;

public enum AllocationStatus {
    /** Target already reached; nothing further is required. */
    COMPLETED,
    /** Funded at the pace needed to hit the target by its deadline. */
    ON_TRACK,
    /** Partially funded; will miss the deadline unless spending changes. */
    AT_RISK,
    /** Nothing left to fund this goal at all. */
    INFEASIBLE
}
