package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Something the user pays every month whether or not they think about it, detected from their own
 * transactions rather than typed in.
 *
 * <p>This is the two-axis case made concrete. Rent and a streaming subscription are equally
 * committed - both are owed next month - and completely different to cut: the lease cannot be
 * changed before the year is out and the subscription can be cancelled this afternoon. A single
 * flexibility ranking cannot say both, which is why {@link SpendCategory} carries commitment and cut
 * speed separately and why this record carries no judgement of its own beyond what the category and
 * the user's own rigidity already say.
 *
 * <p>It names the merchant on purpose. "Cut your Netflix by $19.57" is advice somebody can act on;
 * "cut Going out and fun by $19.57" is not.
 *
 * @param rigidity the category's default, which the user may override per line item afterwards
 * @param nextExpected when the provider expects it again, so a plan can say what is still to come
 *     this month rather than only what has already gone
 */
public record RecurringCommitment(
        String streamId,
        String label,
        SpendCategory category,
        Money monthlyAmount,
        Rigidity rigidity,
        LocalDate nextExpected) {

    public RecurringCommitment {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(monthlyAmount, "monthlyAmount");
        Objects.requireNonNull(rigidity, "rigidity");
    }

    /**
     * Whether the engine may propose a number here at all. Same two conditions the planning side
     * applies, so a commitment cannot look cuttable in one place and locked in another.
     */
    public boolean isCuttable() {
        return rigidity != Rigidity.LOCKED && category.autoSuggestCuts();
    }
}
