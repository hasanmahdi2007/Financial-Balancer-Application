package com.hasan.budget.ingestion.application;

import com.hasan.budget.ingestion.domain.BankConnection;
import com.hasan.budget.ingestion.domain.BankWebhook;
import com.hasan.budget.ingestion.domain.Ledger;
import com.hasan.budget.ingestion.domain.LedgerEntry;
import com.hasan.budget.ingestion.domain.RecurringCommitment;
import com.hasan.budget.ingestion.domain.RecurringStream;
import com.hasan.budget.ingestion.domain.SpendingSummary;
import com.hasan.budget.ingestion.domain.SyncInterrupted;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.port.BankConnections;
import com.hasan.budget.ingestion.port.BankDataProvider;
import com.hasan.budget.ingestion.port.BankLedger;
import com.hasan.budget.ingestion.port.BankLinkProvider;
import com.hasan.budget.ingestion.port.RecurringStreamProvider;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connecting a bank, keeping its data current, and answering questions about what it says.
 *
 * <p><strong>No sync ever happens on a user's request.</strong> Importing a history is hundreds of
 * rows over somebody else's API and can take tens of seconds; a request thread waiting on it is a
 * page that hangs and a connection pool that empties. Everything that could be slow is handed to an
 * executor and the caller returns immediately.
 *
 * <p>The executor is injected rather than an {@code @Async} annotation being relied upon, for two
 * reasons. A test can then prove the request returned before the work started, with no container
 * involved; and an annotation only takes effect through a proxy, so one internal call from another
 * method of this class would silently become synchronous again - a failure that shows up as a slow
 * endpoint in production and never in a test.
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** A sync interrupted by changing data restarts from the beginning; three attempts is plenty. */
    private static final int SYNC_ATTEMPTS = 3;

    private final BankLinkProvider links;
    private final BankDataProvider banks;
    private final RecurringStreamProvider streams;
    private final BankConnections connections;
    private final BankLedger ledger;
    private final AccessTokenCipher cipher;
    private final Executor executor;

    public IngestionService(
            BankLinkProvider links,
            BankDataProvider banks,
            RecurringStreamProvider streams,
            BankConnections connections,
            BankLedger ledger,
            AccessTokenCipher cipher,
            Executor executor) {
        this.links = links;
        this.banks = banks;
        this.streams = streams;
        this.connections = connections;
        this.ledger = ledger;
        this.cipher = cipher;
        this.executor = executor;
    }

    /** A token for the provider's consent widget. The user's bank credentials never reach us. */
    public String startLinking(String userId) {
        return links.createLinkToken(userId);
    }

    /**
     * Completes a connection and returns as soon as it is recorded, with the import already running
     * elsewhere.
     */
    public BankConnection connect(String userId, String publicToken) {
        String accessToken = banks.exchangePublicToken(publicToken);
        String providerItemId = links.itemIdFor(accessToken);
        BankConnection connection =
                connections.connect(userId, providerItemId, cipher.encrypt(accessToken, userId));
        requestSync(connection.id());
        return connection;
    }

    /** Asks for a sync and returns. Never waits for the bank. */
    public void requestSync(long connectionId) {
        executor.execute(() -> quietly("sync", connectionId, () -> syncNow(connectionId)));
    }

    /**
     * Acts on a notification from the provider.
     *
     * <p>A notification about a connection we do not hold is logged and dropped rather than treated
     * as an error: notifications arrive for items that have been disconnected, and an endpoint that
     * fails on them invites the provider to retry forever.
     */
    public void onWebhook(BankWebhook webhook) {
        Optional<BankConnection> connection = connections.findByProviderItemId(webhook.providerItemId());
        if (connection.isEmpty()) {
            log.info("Notification for an unknown connection {}; ignoring", webhook.providerItemId());
            return;
        }
        long connectionId = connection.get().id();
        switch (webhook.action()) {
            case SYNC_TRANSACTIONS -> requestSync(connectionId);
            case REFRESH_RECURRING -> executor.execute(
                    () -> quietly("recurring refresh", connectionId, () -> refreshRecurring(connectionId)));
            case IGNORE -> log.debug("Nothing to do for the notification about connection {}", connectionId);
        }
    }

    /**
     * Pulls every page the provider has, then writes them all at once.
     *
     * <p>Collected before being applied because a sync is only consistent as a whole: the page that
     * adds a settled transaction may be the page after the one that removed its pending version, and
     * stopping between the two would leave the user's spending counted twice. The cursor advances in
     * the same write, so a crash anywhere before it re-reads the same pages rather than skipping
     * them.
     */
    public SyncOutcome syncNow(long connectionId) {
        BankConnection connection = connections
                .find(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("no connection " + connectionId));
        String accessToken = cipher.decrypt(connection.accessToken(), connection.userId());

        for (int attempt = 1; attempt <= SYNC_ATTEMPTS; attempt++) {
            String startedAt = ledger.cursor(connectionId).orElse(null);
            try {
                List<SyncResult> pages = readAllPages(accessToken, startedAt);
                boolean applied = ledger.apply(connectionId, startedAt, pages);
                if (!applied) {
                    log.info("Another sync of connection {} finished first; nothing to write", connectionId);
                    return SyncOutcome.overtaken();
                }
                SyncOutcome outcome = SyncOutcome.of(pages);
                if (outcome.changedAnything()) {
                    refreshRecurring(connectionId, accessToken);
                }
                return outcome;
            } catch (SyncInterrupted e) {
                log.info("Connection {} changed mid-import; starting again ({} of {})",
                        connectionId, attempt, SYNC_ATTEMPTS);
            }
        }
        throw new SyncInterrupted("connection " + connectionId + " kept changing while it was being read");
    }

    private List<SyncResult> readAllPages(String accessToken, String startedAt) {
        List<SyncResult> pages = new ArrayList<>();
        String cursor = startedAt;
        SyncResult page;
        do {
            page = banks.sync(accessToken, cursor);
            pages.add(page);
            cursor = page.nextCursor();
        } while (page.hasMore());
        return pages;
    }

    /** Re-reads detected streams. Separate from a sync because a notification can ask for only this. */
    public void refreshRecurring(long connectionId) {
        BankConnection connection = connections
                .find(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("no connection " + connectionId));
        refreshRecurring(connectionId, cipher.decrypt(connection.accessToken(), connection.userId()));
    }

    private void refreshRecurring(long connectionId, String accessToken) {
        List<RecurringStream> detected = streams.recurringStreams(accessToken);
        ledger.replaceStreams(connectionId, detected);
    }

    public SpendingSummary summaryFor(String userId, YearMonth month) {
        return ledgerFor(userId).summaryFor(month);
    }

    public List<RecurringCommitment> commitmentsFor(String userId) {
        return ledgerFor(userId).commitments();
    }

    public List<LedgerEntry> transactionsIn(String userId, YearMonth month) {
        return ledgerFor(userId).entriesIn(month);
    }

    Ledger ledgerFor(String userId) {
        return Ledger.of(ledger.entriesForUser(userId), ledger.streamsForUser(userId));
    }

    /**
     * Background work reports failure by logging it. Letting it escape would only reach the
     * executor's default handler, and there is no user waiting on the other end to tell.
     */
    private void quietly(String what, long connectionId, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.error("Background {} of connection {} failed", what, connectionId, e);
        }
    }

    /** What one sync did, for logging and for tests. Never carries a token or any transaction detail. */
    public record SyncOutcome(boolean applied, int added, int modified, int removed) {

        static SyncOutcome overtaken() {
            return new SyncOutcome(false, 0, 0, 0);
        }

        static SyncOutcome of(List<SyncResult> pages) {
            int added = pages.stream().mapToInt(page -> page.added().size()).sum();
            int modified = pages.stream().mapToInt(page -> page.modified().size()).sum();
            int removed = pages.stream()
                    .mapToInt(page -> page.removedExternalIds().size())
                    .sum();
            return new SyncOutcome(true, added, modified, removed);
        }

        public boolean changedAnything() {
            return added + modified + removed > 0;
        }
    }
}
