package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Everything the affordability check needs, and nothing else.
 *
 * <p>Note what is absent: a city, a provider, a clock, a user. The allowance arrives already
 * resolved - the user's own figure where they set one, the localised baseline otherwise - for the
 * same reason the surplus calculation receives an effective baseline rather than a
 * {@link com.hasan.budget.shared.SpendCategory} and a data source. The moment this record could see
 * where the number came from, somebody would branch on it here.
 *
 * @param category what is being bought. A parameter rather than an assumption, which is the whole
 *     reason this answers the jacket question as well as the lunch one.
 * @param allowance the whole month's allowance for this category
 * @param spentMonthToDate what has already gone on it this month, including today's earlier spending
 * @param purchase the thing being considered, priced
 * @param ladder the alternatives worth offering instead, which may be empty for a one-off purchase
 * @param asOf the evaluation date, passed in rather than read, so the same inputs always produce the
 *     same answer. An ArchUnit rule enforces that no class here reads a clock.
 */
public record SpendDecisionRequest(
        SpendCategory category,
        Money allowance,
        Money spentMonthToDate,
        TicketEstimate purchase,
        PriceLadder ladder,
        LocalDate asOf) {

    public SpendDecisionRequest {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(allowance, "allowance");
        Objects.requireNonNull(spentMonthToDate, "spentMonthToDate");
        Objects.requireNonNull(purchase, "purchase");
        Objects.requireNonNull(ladder, "ladder");
        Objects.requireNonNull(asOf, "asOf");
        if (allowance.isNegative()) {
            throw new IllegalArgumentException("allowance must not be negative but was " + allowance);
        }
        if (spentMonthToDate.isNegative()) {
            throw new IllegalArgumentException(
                    "spentMonthToDate must not be negative but was " + spentMonthToDate);
        }
    }

    /**
     * What is left of the allowance. Deliberately not clamped: a user really can be over their
     * allowance by the tenth, and hiding that would have the engine quote a daily rate they do not
     * have.
     */
    public Money remainingBudget() {
        return allowance.minus(spentMonthToDate);
    }

    /**
     * Days left in the month counting today. Today counts because the purchase being considered is
     * today's - on the last day of the month that is one day left, not zero, and getting this wrong
     * is a division by zero rather than an answer that is merely slightly off.
     */
    public int remainingDays() {
        return asOf.lengthOfMonth() - asOf.getDayOfMonth() + 1;
    }
}
