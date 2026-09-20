package com.hasan.budget.profile.domain;

/** How the user chose to limit what the app may plan with. */
public enum ConsiderationMode {
    /** A share of the visible balance, for example 60%. */
    PERCENTAGE,
    /** A named figure, for example $50,000, clamped to the real balance if it exceeds it. */
    ABSOLUTE,
    /**
     * The whole stated total. Used when the user typed their money in rather than connecting a bank:
     * they have already chosen what is in scope by choosing what to type, so asking them for a
     * further percentage of it would be incoherent.
     */
    WHOLE
}
