package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.util.Objects;

/**
 * What a transaction is, on two independent axes, plus one fact about transfers.
 *
 * <p>Treating "kind of money movement" as a value of "spend category" is what causes the double
 * count. Moving $500 into savings is not spending, and paying a credit-card bill settles purchases
 * already counted when they were made - counting either as spend subtracts the same money twice.
 *
 * <p>{@code towardsSavings} exists because "not spending" is not specific enough to report with.
 * Money moved to savings and a credit-card bill being paid are both internal transfers, and adding
 * them together would tell a user they had saved the amount they just paid off their card. Only the
 * provider knows which is which, so the mapping table records it and it travels with the
 * classification rather than being re-derived downstream from something it cannot see.
 *
 * @param category meaningful only when {@code kind} is {@link TransactionKind#SPEND}; null otherwise
 * @param towardsSavings true when this movement adds to savings or investments - and in the
 *     provider's sign convention a negative amount with this flag is money coming back out, so the
 *     two net off across a month rather than both counting as saving
 */
public record Classification(TransactionKind kind, SpendCategory category, boolean towardsSavings) {

    public Classification {
        Objects.requireNonNull(kind, "kind");
        if (kind == TransactionKind.SPEND && category == null) {
            throw new IllegalArgumentException("SPEND transactions must carry a category");
        }
        if (towardsSavings && kind != TransactionKind.TRANSFER_INTERNAL) {
            throw new IllegalArgumentException(
                    "only a transfer between the user's own accounts can be saving, but this was " + kind);
        }
    }

    public static Classification spend(SpendCategory category) {
        return new Classification(TransactionKind.SPEND, category, false);
    }

    public static Classification notSpending(TransactionKind kind) {
        return new Classification(kind, null, false);
    }

    /** A transfer that adds to what the user has put by, rather than settling something they owe. */
    public static Classification savings() {
        return new Classification(TransactionKind.TRANSFER_INTERNAL, null, true);
    }
}
