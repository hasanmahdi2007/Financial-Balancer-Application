package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A bank transaction after the provider's quirks have been normalised away.
 *
 * <p>Crucially, {@code amount} here is <strong>positive for money leaving the account</strong>,
 * matching Plaid's own convention rather than intuition: a $700 deposit arrives from Plaid as -700
 * and stays negative here, and it is the display layer that flips it. Normalising the sign at this
 * boundary rather than at the edge would hide the trap instead of containing it.
 *
 * @param merchantEntityId a stable merchant identifier, so aggregating spend per merchant later
 *     needs no string matching. Persisted from the first import to avoid a backfill.
 */
public record NormalisedTransaction(
        String externalId,
        String accountId,
        LocalDate date,
        Money amount,
        String merchantName,
        String merchantEntityId,
        Double latitude,
        Double longitude,
        Classification classification,
        boolean pending) {

    public NormalisedTransaction {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(classification, "classification");
    }

    /** True when money moved out of the account, using the provider's sign convention. */
    public boolean isOutflow() {
        return amount.isPositive();
    }
}
