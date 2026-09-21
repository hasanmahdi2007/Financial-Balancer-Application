package com.hasan.budget.profile.domain;

/**
 * How the user chose to limit what the app may plan with.
 *
 * <p>Each constant carries whether onboarding has to ask what share is in scope, so that the
 * question is driven by a table rather than by a condition repeated at every call site. The answer
 * turns on one thing: whether the app can see money the user never chose to show it.
 */
public enum ConsiderationMode {
    /** A share of the visible balance, for example 60%. */
    PERCENTAGE(true),
    /** A named figure, for example $50,000, clamped to the real balance if it exceeds it. */
    ABSOLUTE(true),
    /**
     * The whole stated total. Used when the user typed their money in rather than connecting a bank:
     * they have already chosen what is in scope by choosing what to type, so asking them for a
     * further percentage of it would be incoherent.
     */
    WHOLE(false);

    private final boolean asksWhatShareIsInScope;

    ConsiderationMode(boolean asksWhatShareIsInScope) {
        this.asksWhatShareIsInScope = asksWhatShareIsInScope;
    }

    /**
     * Whether onboarding must ask the user how much of their money the app may plan with. False
     * where the user has already answered it by choosing what to type.
     */
    public boolean asksWhatShareIsInScope() {
        return asksWhatShareIsInScope;
    }
}
