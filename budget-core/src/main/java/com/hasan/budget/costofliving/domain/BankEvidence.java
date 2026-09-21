package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * What the user's own transactions say they actually spend on one category.
 *
 * <p>Deliberately a bare pair rather than anything from the ingestion module. This packet needs one
 * number per category and nothing else, and widening the seam to "pass me the transactions" would
 * drag Plaid's shape into the cost-of-living validator and never be narrowed again. Whoever has the
 * bank data reduces it to this; the validator never learns where it came from.
 *
 * @param observedMonthly a detected recurring amount, or a median of recent months - the caller's
 *     judgement, because only the caller knows which is meaningful for that category.
 */
public record BankEvidence(SpendCategory category, Money observedMonthly) {

    public BankEvidence {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(observedMonthly, "observedMonthly");
    }
}
