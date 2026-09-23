package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.util.EnumMap;
import java.util.Map;

/**
 * What it means about a transaction that it repeats.
 *
 * <p>Two rules, both of which exist because the recorded sandbox showed the category alone getting
 * them wrong.
 *
 * <p><strong>A merchant that pays you every week is an employer.</strong> This user's payroll
 * arrives from Sweetgreen tagged {@code FOOD_AND_DRINK_RESTAURANT}, because the employer is a
 * restaurant. Read as a category it is an inflow at a restaurant, which is a refund, so $810 a week
 * of pay would be booked as a discount on eating out - the user's income would vanish and their
 * dining bill would go negative. A refund does not arrive every Saturday, and that is the signal:
 * an inflow the provider has detected as recurring is income, whatever the merchant sells.
 *
 * <p><strong>Something discretionary that repeats every month is a subscription.</strong> Netflix
 * arrives as {@code ENTERTAINMENT_TV_AND_MOVIES}, which is right for a cinema ticket and wrong for a
 * standing charge: the cinema ticket is this month's choice, while the subscription is owed until
 * somebody cancels it. Moving it makes it a fixed commitment that is still a cut candidate, which is
 * exactly the case the two axes exist for.
 *
 * <p>The table below is the whole of the second rule. Categories not in it keep their own meaning
 * when they repeat, and that is deliberate rather than an omission: a weekly coffee is a habit
 * rather than a subscription, and calling it fixed would take it out of the spending the user can
 * actually change this month. Groceries and utilities stay measured against their city.
 */
public final class RecurringPolicy {

    private static final Map<SpendCategory, SpendCategory> WHEN_IT_REPEATS = whenItRepeats();

    private RecurringPolicy() {}

    private static Map<SpendCategory, SpendCategory> whenItRepeats() {
        Map<SpendCategory, SpendCategory> table = new EnumMap<>(SpendCategory.class);
        // Streaming, music, games: billed monthly until cancelled.
        table.put(SpendCategory.ENTERTAINMENT, SpendCategory.SUBSCRIPTIONS);
        // Software, domains, storage and the rest of what we could not identify: repeating means billed.
        table.put(SpendCategory.OTHER, SpendCategory.SUBSCRIPTIONS);
        return Map.copyOf(table);
    }

    /**
     * Revises what a transaction means, given that it belongs to a detected stream.
     *
     * @param direction which way the stream flows, not which way this one transaction went
     */
    public static Classification inStream(Classification asClassified, RecurringStream.Direction direction) {
        if (asClassified.kind() != TransactionKind.SPEND) {
            return asClassified;
        }
        if (direction == RecurringStream.Direction.MONEY_IN) {
            return Classification.notSpending(TransactionKind.INCOME);
        }
        SpendCategory repeating = WHEN_IT_REPEATS.get(asClassified.category());
        return repeating == null ? asClassified : Classification.spend(repeating);
    }
}
