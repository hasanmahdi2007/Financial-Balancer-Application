package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.util.Objects;

/**
 * What a transaction is, on two independent axes.
 *
 * <p>Treating "kind of money movement" as a value of "spend category" is what causes the double
 * count. Moving $500 into savings is not spending, and paying a credit-card bill settles purchases
 * already counted when they were made - counting either as spend subtracts the same money twice.
 *
 * @param category meaningful only when {@code kind} is {@link TransactionKind#SPEND}; null otherwise
 */
public record Classification(TransactionKind kind, SpendCategory category) {

    public Classification {
        Objects.requireNonNull(kind, "kind");
        if (kind == TransactionKind.SPEND && category == null) {
            throw new IllegalArgumentException("SPEND transactions must carry a category");
        }
    }

    public static Classification spend(SpendCategory category) {
        return new Classification(TransactionKind.SPEND, category);
    }

    public static Classification notSpending(TransactionKind kind) {
        return new Classification(kind, null);
    }
}
