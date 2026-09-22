package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.SpendCategory;
import java.util.EnumMap;
import java.util.Map;

/**
 * The real way to spend less on each kind of thing, in one sentence, for the lines the engine is not
 * allowed to cut.
 *
 * <p>These exist because "cannot be changed" is true of the monthly amount and false of the thing
 * itself. Rent has no monthly lever, but a roommate or a renegotiated lease is one; a locked gym
 * membership has no monthly lever, but the off-peak tariff is one. Saying nothing at all about a
 * locked line leaves the user's largest outflows entirely unaddressed.
 *
 * <p>Every lever is <strong>qualitative on purpose</strong>. A hint carrying a number would sooner or
 * later be added to a total by a caller who took it for a saving already made, and the plan would then
 * promise money nobody has found. The percentages that do appear sit inside the sentence, as context
 * for the user, and never leave this class as a figure - which is why {@link SavingHint} holds a
 * string and no {@link com.hasan.budget.shared.Money} anywhere.
 *
 * <p>One row per category, and the check below fails at class initialisation if a category is ever
 * added without one. That keeps "adding a category is one row" true rather than letting it become a
 * silent gap where a locked line offers no advice at all.
 */
public enum SavingLever {

    RENT(SpendCategory.RENT,
            "a roommate, or renegotiating the lease at renewal, is the only real lever here - the "
                    + "monthly figure itself will not move"),
    DEBT_PAYMENT(SpendCategory.DEBT_PAYMENT,
            "the instalment is fixed, so the lever is the loan itself: refinancing, consolidating, or "
                    + "overpaying now to shorten it"),
    TAX_RESERVE(SpendCategory.TAX_RESERVE,
            "this is money you owe rather than money you spend, so treat the amount as fixed and check "
                    + "whether you are setting aside more than the bill will actually be"),
    HEALTHCARE(SpendCategory.HEALTHCARE,
            "comparing plans at renewal, and asking for generic medication, are where this actually "
                    + "moves"),
    SUBSCRIPTIONS(SpendCategory.SUBSCRIPTIONS,
            "off-peak or annual-paid memberships are usually 20-30% cheaper than the monthly rate for "
                    + "exactly the same thing"),
    UTILITIES(SpendCategory.UTILITIES,
            "off-peak tariffs and replacing the bulbs you leave on typically cut 10-15%, and comparing "
                    + "providers is worth an hour once a year"),
    GROCERIES(SpendCategory.GROCERIES,
            "own-brand staples and one planned weekly shop instead of several small ones is where this "
                    + "moves, not skipping meals"),
    TRANSPORT_FUEL(SpendCategory.TRANSPORT_FUEL,
            "a monthly pass usually beats single fares once you travel more than about three days a "
                    + "week"),
    DINING_OUT(SpendCategory.DINING_OUT,
            "lunch menus and cooking one more evening a week move this further than giving up coffee "
                    + "does"),
    ENTERTAINMENT(SpendCategory.ENTERTAINMENT,
            "off-peak tickets and the free things your city already runs cover most of this without "
                    + "giving up the evening"),
    CLOTHING(SpendCategory.CLOTHING,
            "end-of-season and second-hand are where this moves, and waiting a week usually settles "
                    + "whether you wanted it"),
    OTHER(SpendCategory.OTHER,
            "we could not tell what this spending was, so the first lever is naming it - once it has a "
                    + "name there is something to decide about");

    private static final Map<SpendCategory, SavingLever> BY_CATEGORY = indexByCategory();

    private final SpendCategory category;
    private final String lever;

    SavingLever(SpendCategory category, String lever) {
        this.category = category;
        this.lever = lever;
    }

    public SpendCategory category() {
        return category;
    }

    /** The sentence shown to the user. Qualitative, and never a number the plan could add up. */
    public String lever() {
        return lever;
    }

    /** The lever for a category. Every category has exactly one, guaranteed at class load. */
    public static SavingLever forCategory(SpendCategory category) {
        return BY_CATEGORY.get(category);
    }

    private static Map<SpendCategory, SavingLever> indexByCategory() {
        Map<SpendCategory, SavingLever> byCategory = new EnumMap<>(SpendCategory.class);
        for (SavingLever lever : values()) {
            if (byCategory.put(lever.category, lever) != null) {
                throw new IllegalStateException("two levers claim " + lever.category);
            }
        }
        for (SpendCategory category : SpendCategory.values()) {
            if (!byCategory.containsKey(category)) {
                throw new IllegalStateException(
                        category + " has no saving lever, so a locked line there would offer no advice");
            }
        }
        return byCategory;
    }
}
