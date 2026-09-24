package com.hasan.budget.web;

/**
 * How long a name the user types for something may be: a goal, a named commitment, a city we do not
 * list.
 *
 * <p>Each of these is shown back on a screen as a label, and none needs more than a few words. The
 * columns holding them are unbounded {@code TEXT}, so without a limit here any signed-in caller could
 * store megabytes per row and have every plan that mentions it carry the lot. One limit and one
 * sentence for all three, because a user who is told 100 characters for a goal and 60 for a gym
 * membership has been given a rule to learn for no reason.
 */
public final class NameLength {

    /** Generous for a label, and still far short of anything that would matter to storage. */
    public static final int MAX = 100;

    private NameLength() {}

    /**
     * @param name what the user typed; blank and missing are the caller's to handle, with wording that
     *     suits the thing being named
     * @param what what is being named, as the user would say it: "the goal's name"
     */
    public static void check(String name, String what) {
        if (name != null && name.strip().length() > MAX) {
            throw new IllegalArgumentException(
                    "Keep %s to %d characters or fewer.".formatted(what, MAX));
        }
    }
}
