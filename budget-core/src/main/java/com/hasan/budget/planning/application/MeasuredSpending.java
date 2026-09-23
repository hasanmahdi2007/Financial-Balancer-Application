package com.hasan.budget.planning.application;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;

/**
 * What a connected bank says a month actually cost, category by category.
 *
 * <p>It carries the month it describes because the plan has to be able to say so. "Groceries $612"
 * is a claim about the user's life; "Groceries $612, which is what you spent in August" is a claim
 * they can check, and the difference between the two is whether they trust the rest of the plan.
 *
 * <p>The month is always a <em>complete</em> one, never the month in progress. Reading the current
 * month on the 3rd would price groceries off three days of it, and the plan would confidently build
 * a year around a figure that is wrong by a factor of ten - always in the direction of promising the
 * user more than they have.
 *
 * @param byCategory only what was measured. A category the bank saw nothing in is absent rather than
 *     zero, because "you spent nothing on healthcare" and "no healthcare payment reached us" lead to
 *     very different plans, and only the second one is true.
 */
public record MeasuredSpending(YearMonth month, Map<SpendCategory, Money> byCategory) {

    public MeasuredSpending {
        Objects.requireNonNull(month, "month");
        byCategory = Map.copyOf(byCategory);
        if (byCategory.containsKey(SpendCategory.TAX_RESERVE)) {
            throw new IllegalArgumentException(
                    "tax set aside is worked out from the tax rate, not read off a bank statement");
        }
    }

    /** Nobody has connected a bank, or the month it would describe holds nothing. */
    public static MeasuredSpending none(YearMonth month) {
        return new MeasuredSpending(month, Map.of());
    }

    public boolean isEmpty() {
        return byCategory.isEmpty();
    }

    /** Null rather than empty, so the caller writes one branch and not two. */
    public Money in(SpendCategory category) {
        return byCategory.get(category);
    }
}
