package com.hasan.budget.ingestion.domain;

import java.util.Objects;

/**
 * One stored transaction, and which connected bank it came from.
 *
 * <p>The connection is carried beside the transaction rather than inside it because
 * {@link NormalisedTransaction} is a frozen contract shared with other packets, and because it is
 * genuinely a property of the import rather than of the purchase. It matters for one rule: two
 * halves of the same transfer can only be the same money if they came from the same connected
 * institution, and matching across two banks would collapse a real payment into nothing.
 */
public record LedgerEntry(long connectionId, NormalisedTransaction transaction) {

    public LedgerEntry {
        Objects.requireNonNull(transaction, "transaction");
    }

    /** A copy carrying a revised classification, used when a recurring stream changes what a row means. */
    public LedgerEntry reclassifiedAs(Classification classification) {
        NormalisedTransaction t = transaction;
        return new LedgerEntry(
                connectionId,
                new NormalisedTransaction(
                        t.externalId(),
                        t.accountId(),
                        t.date(),
                        t.amount(),
                        t.merchantName(),
                        t.merchantEntityId(),
                        t.latitude(),
                        t.longitude(),
                        classification,
                        t.pending()));
    }

    public Classification classification() {
        return transaction.classification();
    }
}
