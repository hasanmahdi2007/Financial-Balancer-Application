package com.hasan.budget.ingestion.support;

import com.hasan.budget.ingestion.domain.BankConnection;
import com.hasan.budget.ingestion.domain.EncryptedToken;
import com.hasan.budget.ingestion.domain.LedgerEntry;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.ingestion.domain.RecurringStream;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.port.BankConnections;
import com.hasan.budget.ingestion.port.BankLedger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The store, in memory, for tests that must stay in the fast tier.
 *
 * <p>It is held to the real thing's behaviour rather than trusted: {@code BankLedgerContract} runs
 * the same cases against this and against Postgres. That matters because the properties the fast
 * tests lean on - idempotent writes, a compare-and-set cursor, deletions scoped to one connection -
 * are exactly the ones a convenient fake would quietly not have.
 */
public class InMemoryBankStore implements BankConnections, BankLedger {

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, BankConnection> connections = new LinkedHashMap<>();
    private final Map<Long, String> cursors = new LinkedHashMap<>();
    private final Map<Long, Map<String, NormalisedTransaction>> transactions = new LinkedHashMap<>();
    private final Map<Long, List<RecurringStream>> streams = new LinkedHashMap<>();

    @Override
    public synchronized BankConnection connect(
            String userId, String providerItemId, EncryptedToken accessToken) {
        Optional<BankConnection> existing = findByProviderItemId(providerItemId);
        if (existing.isPresent()) {
            if (!existing.get().userId().equals(userId)) {
                throw new IllegalStateException(
                        "bank connection " + providerItemId + " is already held by another account");
            }
            BankConnection refreshed = new BankConnection(
                    existing.get().id(), userId, providerItemId, accessToken);
            connections.put(refreshed.id(), refreshed);
            return refreshed;
        }
        BankConnection created = new BankConnection(nextId.getAndIncrement(), userId, providerItemId, accessToken);
        connections.put(created.id(), created);
        return created;
    }

    @Override
    public synchronized Optional<BankConnection> find(long connectionId) {
        return Optional.ofNullable(connections.get(connectionId));
    }

    @Override
    public synchronized Optional<BankConnection> findByProviderItemId(String providerItemId) {
        return connections.values().stream()
                .filter(connection -> connection.providerItemId().equals(providerItemId))
                .findFirst();
    }

    @Override
    public synchronized List<BankConnection> forUser(String userId) {
        return connections.values().stream()
                .filter(connection -> connection.userId().equals(userId))
                .toList();
    }

    @Override
    public synchronized Optional<String> cursor(long connectionId) {
        return Optional.ofNullable(cursors.get(connectionId));
    }

    @Override
    public synchronized boolean apply(long connectionId, String expectedCursor, List<SyncResult> pages) {
        if (pages.isEmpty()) {
            return true;
        }
        if (!Objects.equals(cursors.get(connectionId), expectedCursor)) {
            return false;
        }
        cursors.put(connectionId, pages.get(pages.size() - 1).nextCursor());
        Map<String, NormalisedTransaction> held =
                transactions.computeIfAbsent(connectionId, id -> new LinkedHashMap<>());
        for (SyncResult page : pages) {
            page.added().forEach(transaction -> held.put(transaction.externalId(), transaction));
            page.modified().forEach(transaction -> held.put(transaction.externalId(), transaction));
            page.removedExternalIds().forEach(held::remove);
        }
        return true;
    }

    @Override
    public synchronized void replaceStreams(long connectionId, List<RecurringStream> detected) {
        streams.put(connectionId, List.copyOf(detected));
    }

    @Override
    public synchronized List<LedgerEntry> entriesForUser(String userId) {
        List<LedgerEntry> entries = new ArrayList<>();
        for (BankConnection connection : forUser(userId)) {
            transactions.getOrDefault(connection.id(), Map.of()).values().stream()
                    .map(transaction -> new LedgerEntry(connection.id(), transaction))
                    .forEach(entries::add);
        }
        entries.sort(java.util.Comparator.comparing(
                        (LedgerEntry entry) -> entry.transaction().date())
                .thenComparing(entry -> entry.transaction().externalId()));
        return List.copyOf(entries);
    }

    @Override
    public synchronized List<RecurringStream> streamsForUser(String userId) {
        List<RecurringStream> found = new ArrayList<>();
        for (BankConnection connection : forUser(userId)) {
            found.addAll(streams.getOrDefault(connection.id(), List.of()));
        }
        return List.copyOf(found);
    }
}
