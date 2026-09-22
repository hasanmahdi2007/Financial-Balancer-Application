package com.hasan.budget.planning.domain.decision;

/**
 * The two things a user can actually do when a plan will not balance.
 *
 * <p>There is no third. Every other apparent option is one of these two wearing a disguise, and the
 * alternative the engine must never take is to balance the arithmetic quietly by cutting below a floor
 * or touching something the user locked. A budget that adds up because the engine ignored an
 * instruction is worse than one that admits it does not add up.
 *
 * <p>Both are worded for a person and neither names an internal concept. "The considered share" is
 * what the code calls it; what the user is being asked is whether to count more of their savings as
 * money they are willing to spend this month.
 */
public enum ResolutionOption {

    /** Widen the pool the plan may draw on, by counting more of the balance as spendable. */
    COUNT_MORE_OF_YOUR_BALANCE(
            "Use more of your savings this month",
            "You can tell us to count more of your balance as money you are willing to spend. "
                    + "Nothing moves by itself - it only changes what we plan around."),

    /** Give a goal more time, which frees what it was taking each month. */
    GIVE_A_GOAL_MORE_TIME(
            "Push one of your goals back",
            "Giving a goal a later date frees up what it was taking each month. We will show you "
                    + "exactly what that costs you in time before you decide.");

    private final String label;
    private final String meaning;

    ResolutionOption(String label, String meaning) {
        this.label = label;
        this.meaning = meaning;
    }

    /** What to call this in the interface. */
    public String label() {
        return label;
    }

    /** What choosing it actually does, in the user's own terms. */
    public String meaning() {
        return meaning;
    }
}
