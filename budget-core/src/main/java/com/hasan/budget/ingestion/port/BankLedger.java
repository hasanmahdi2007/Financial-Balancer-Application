package com.hasan.budget.ingestion.port;

import com.hasan.budget.ingestion.domain.LedgerEntry;
import com.hasan.budget.ingestion.domain.RecurringStream;
import com.hasan.budget.ingestion.domain.SyncResult;
import java.util.List;
import java.util.Optional;

/**
 * Where imported transactions, detected streams and the sync cursor live.
 *
 * <p>The cursor is stored here rather than beside the caller because it is only meaningful together
 * with the rows it accounts for: a cursor saved without its transactions would skip them forever,
 * and transactions saved without their cursor would arrive twice.
 */
public interface BankLedger {

    /** Empty when this connection has never been synced, which is what asks for a full import. */
    Optional<String> cursor(long connectionId);

    /**
     * Applies a completed sync - every page of it - and advances the cursor, all or nothing.
     *
     * <p>Two properties this has to have, and both are why it is one method rather than three.
     *
     * <p><strong>Idempotent.</strong> Rows are written by identity, so the same page applied twice
     * leaves exactly what it left the first time. A duplicate notification, a retry after a timeout
     * and a restart mid-import all converge on the same ledger.
     *
     * <p><strong>Refuses to apply out of order.</strong> {@code expectedCursor} is the cursor the
     * sync started from; if it no longer matches, another sync has already moved this connection
     * forward and these pages describe an older world. Writing them would resurrect transactions
     * that have since been removed. Two syncs of the same connection can therefore race safely: one
     * wins, the other reports that it did nothing.
     *
     * @param pages in the order the provider returned them
     * @return false when another sync got there first and nothing was written
     */
    boolean apply(long connectionId, String expectedCursor, List<SyncResult> pages);

    /**
     * Replaces every known stream for a connection.
     *
     * <p>Replaced rather than merged: the provider re-detects streams from scratch each time, and a
     * subscription that has been cancelled disappears from its answer rather than being marked
     * ended. Merging would leave the cancelled one in the plan forever.
     */
    void replaceStreams(long connectionId, List<RecurringStream> streams);

    List<LedgerEntry> entriesForUser(String userId);

    List<RecurringStream> streamsForUser(String userId);
}
