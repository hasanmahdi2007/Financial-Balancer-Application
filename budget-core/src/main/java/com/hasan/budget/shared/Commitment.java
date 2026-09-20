package com.hasan.budget.shared;

/** Whether an expense is contractually locked in for the month, regardless of how easy it is to cut. */
public enum Commitment {
    /** Owed this month whatever happens: a lease, an insurance premium, a loan instalment. */
    FIXED,
    /** Varies with behaviour, so the amount observed this month is not the amount owed. */
    VARIABLE
}
