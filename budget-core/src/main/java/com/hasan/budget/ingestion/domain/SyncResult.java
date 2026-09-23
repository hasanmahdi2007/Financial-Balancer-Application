package com.hasan.budget.ingestion.domain;

import java.util.List;
import java.util.Objects;

/**
 * One page of incremental changes from the bank, plus the cursor to resume from.
 *
 * <p>Lives in {@code domain} rather than nested inside the port because a port package holds
 * contracts only - an ArchUnit rule enforces that every type there is an interface, so that a port
 * stays a thing you can implement rather than a thing you inherit data shapes from.
 *
 * @param removedExternalIds transactions the bank has retracted. Ignoring these leaves spending in
 *     the plan that no longer exists.
 * @param accounts where the money sits, as of this sync. Carried here because the provider sends it
 *     alongside the transactions anyway: asking separately later would mean a second call to
 *     somebody else's API at the moment a user is waiting for a page.
 */
public record SyncResult(
        List<NormalisedTransaction> added,
        List<NormalisedTransaction> modified,
        List<String> removedExternalIds,
        List<AccountSnapshot> accounts,
        String nextCursor,
        boolean hasMore) {

    public SyncResult {
        added = List.copyOf(added);
        modified = List.copyOf(modified);
        removedExternalIds = List.copyOf(removedExternalIds);
        accounts = List.copyOf(accounts);
        Objects.requireNonNull(nextCursor, "nextCursor");
    }

    /** A page that carries no account information, for providers or tests that report only rows. */
    public static SyncResult of(
            List<NormalisedTransaction> added,
            List<NormalisedTransaction> modified,
            List<String> removedExternalIds,
            String nextCursor,
            boolean hasMore) {
        return new SyncResult(added, modified, removedExternalIds, List.of(), nextCursor, hasMore);
    }
}
