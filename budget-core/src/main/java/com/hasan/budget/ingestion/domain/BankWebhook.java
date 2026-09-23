package com.hasan.budget.ingestion.domain;

import java.util.Objects;

/**
 * A notification from the provider that something about a connection has changed.
 *
 * <p>Reduced to a connection and an instruction on purpose. A webhook endpoint is the one door into
 * this service that is not behind the gateway, so nothing a webhook <em>says</em> is trusted: it is
 * only a nudge to go and ask the provider directly, over the authenticated connection, what changed.
 *
 * <p>That is why there is no "these transactions were removed" variant here even though the provider
 * sends one. Acting on a list of identifiers in an unauthenticated request body would let anyone who
 * learns an item id delete a user's spending, and deleted spending makes a plan look better than it
 * is. Answering the same notification by re-syncing reaches the identical end state through the one
 * channel that is actually authenticated.
 */
public record BankWebhook(String providerItemId, Action action) {

    public BankWebhook {
        Objects.requireNonNull(action, "action");
    }

    public enum Action {
        /** Ask the provider what changed, whatever the notification claimed. */
        SYNC_TRANSACTIONS,
        /** Re-read the detected recurring streams; the transactions themselves are unchanged. */
        REFRESH_RECURRING,
        /** Nothing for this module to do. Recorded rather than silently dropped. */
        IGNORE
    }

    public static BankWebhook ignored(String providerItemId) {
        return new BankWebhook(providerItemId, Action.IGNORE);
    }
}
