package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;

/**
 * One month of a user's bank data, reduced to the few numbers a plan needs.
 *
 * <p>Everything here is money that actually moved in that month. Nothing is a forecast, nothing is
 * capped, and nothing is compared to a baseline - that is the planning side's job, and mixing the
 * two is how a measurement turns into a recommendation nobody can audit.
 *
 * @param spendingByCategory real consumption only. Transfers between the user's own accounts and
 *     credit-card bill payments are not here, because the purchases they settle were counted when
 *     they were made; counting both subtracts $600 for $300 of groceries. Refunds net against their
 *     own category.
 * @param income what arrived as earnings or deposits, shown the way a person reads it - positive.
 *     Money borrowed is not here: a cash advance is not income, and counting it would make the
 *     surplus look better than it is.
 * @param fees bank charges and card interest. Real money out, kept separate so it can be named
 *     rather than buried in a category the user would read as their own choice.
 * @param alreadySaving what the user moved into savings or investments, net of anything they took
 *     back out. <strong>Displayed, never subtracted</strong>: it is not spending, and subtracting it
 *     would count the same money twice the moment the goal it funds is planned for. Credit-card
 *     payments are not in here even though they are transfers too - paying off a card is settling a
 *     debt, and adding the two together would congratulate somebody for clearing their balance. It
 *     can be negative, in a month where more came out of savings than went in, and that is the
 *     truth rather than something to hide.
 * @param pendingSpending the part of {@code spendingByCategory} that has not settled. Included
 *     because the money is as good as gone, and reported separately because the amount can still
 *     change - a restaurant authorises the bill and settles the tip.
 */
public record SpendingSummary(
        YearMonth month,
        Map<SpendCategory, Money> spendingByCategory,
        Money income,
        Money fees,
        Money alreadySaving,
        Money pendingSpending,
        int transactionCount) {

    public SpendingSummary {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(fees, "fees");
        Objects.requireNonNull(alreadySaving, "alreadySaving");
        Objects.requireNonNull(pendingSpending, "pendingSpending");
        spendingByCategory = Map.copyOf(spendingByCategory);
    }

    /** Zero rather than absent, so a caller never has to decide what a missing category means. */
    public Money spentOn(SpendCategory category) {
        return spendingByCategory.getOrDefault(category, Money.ZERO);
    }

    /** Everything consumed this month, fees included. */
    public Money totalSpending() {
        return spendingByCategory.values().stream().reduce(Money.ZERO, Money::plus).plus(fees);
    }
}
