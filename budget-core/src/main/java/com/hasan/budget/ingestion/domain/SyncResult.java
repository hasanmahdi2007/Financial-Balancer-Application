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
 */
public record SyncResult(
        List<NormalisedTransaction> added,
        List<NormalisedTransaction> modified,
        List<String> removedExternalIds,
        String nextCursor,
        boolean hasMore) {

    public SyncResult {
        added = List.copyOf(added);
        modified = List.copyOf(modified);
        removedExternalIds = List.copyOf(removedExternalIds);
        Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
