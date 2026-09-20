package com.hasan.budget.shared;

/**
 * What a money movement actually is, independent of which {@link SpendCategory} it looks like.
 *
 * <p>This axis exists because treating "kind of movement" as a value of "spend category" causes a
 * double count. Moving $500 into savings is not spending, and paying a credit-card bill settles
 * purchases that were already counted when they were made — counting either as spend subtracts the
 * same money twice and understates the surplus.
 */
public enum TransactionKind {
    /** Real consumption. The only kind that becomes a category observation. */
    SPEND,
    /** Money moving between accounts the same user owns, including credit-card payments. */
    TRANSFER_INTERNAL,
    /** Money leaving to someone else without buying anything, such as a gift sent. */
    TRANSFER_EXTERNAL,
    /** Money arriving as earnings or deposits. */
    INCOME,
    /** A reversal of earlier spending. */
    REFUND,
    /** A bank charge. Counted as spend, but flagged so it can be surfaced separately. */
    FEE
}
