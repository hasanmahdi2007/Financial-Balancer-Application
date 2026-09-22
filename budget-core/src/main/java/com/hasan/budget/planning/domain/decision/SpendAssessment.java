package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;
import java.util.Optional;

/**
 * The answer, with every figure it was derived from, so the interface can explain itself rather than
 * assert a verdict.
 *
 * @param sustainableDaily what is left, spread across the days left. The number the verdict compares
 *     against.
 * @param catchUpPlan how the rest of the month absorbs this purchase, or null when the purchase is
 *     already within the daily rate and there is nothing to absorb
 * @param cheaperOption the next rung down with its own verdict, or null when the user already picked
 *     the cheapest option on the ladder, or brought no ladder at all
 */
public record SpendAssessment(
        SpendCategory category,
        TicketEstimate purchase,
        Money allowance,
        Money spentMonthToDate,
        Money remainingBudget,
        int remainingDays,
        Money sustainableDaily,
        SpendVerdict verdict,
        CatchUpPlan catchUpPlan,
        CheaperOption cheaperOption) {

    public SpendAssessment {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(purchase, "purchase");
        Objects.requireNonNull(allowance, "allowance");
        Objects.requireNonNull(remainingBudget, "remainingBudget");
        Objects.requireNonNull(sustainableDaily, "sustainableDaily");
        Objects.requireNonNull(verdict, "verdict");
    }

    /** Present whenever following through on this purchase needs the coming days to run lower. */
    public Optional<CatchUpPlan> catchUp() {
        return Optional.ofNullable(catchUpPlan);
    }

    /** Present unless this was already the cheapest thing on offer. */
    public Optional<CheaperOption> cheaper() {
        return Optional.ofNullable(cheaperOption);
    }
}
