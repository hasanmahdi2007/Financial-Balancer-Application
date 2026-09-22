package com.hasan.budget.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.ingestion.domain.BankConnection;
import com.hasan.budget.ingestion.domain.BankWebhook;
import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.ingestion.domain.RecurringStream;
import com.hasan.budget.ingestion.domain.SyncInterrupted;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.port.BankDataProvider;
import com.hasan.budget.ingestion.port.BankLinkProvider;
import com.hasan.budget.ingestion.port.RecurringStreamProvider;
import com.hasan.budget.ingestion.support.InMemoryBankStore;
import com.hasan.budget.ingestion.support.LogCapture;
import com.hasan.budget.ingestion.support.TestExecutors;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a sync behaves when the world does not cooperate: many pages, two syncs at once, data
 * changing underneath, notifications about strangers.
 *
 * <p>Driven by a scripted provider rather than the recorded sandbox, because these cases are about
 * sequences of responses that a real bank produces only occasionally and never on demand.
 */
class IngestionServiceTest {

    private static final String USER = "user-1";
    private static final String ITEM = "item-1";

    private final InMemoryBankStore store = new InMemoryBankStore();
    private final AccessTokenCipher cipher = new AccessTokenCipher(aKey());

    @Test
    @DisplayName("every page of a sync is read before anything is written")
    void allPagesAreAppliedAsOneUnit() {
        ScriptedBank bank = new ScriptedBank(
                new SyncResult(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", true),
                new SyncResult(List.of(purchase("t-2", "20.00")), List.of(), List.of(), "c2", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());
        long connectionId = ingestion.connect(USER, "public-token").id();

        assertThat(store.entriesForUser(USER)).hasSize(2);
        assertThat(store.cursor(connectionId)).contains("c2");
        assertThat(bank.cursors).containsExactly(null, "c1");
    }

    @Test
    @DisplayName("a sync that loses the race reports that it wrote nothing")
    void anOvertakenSyncIsHarmless() {
        ScriptedBank bank = new ScriptedBank(
                new SyncResult(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());
        long connectionId = ingestion.connect(USER, "public-token").id();

        // A second sync finishes while this one is still reading its pages - the only moment the
        // race is real, since each attempt re-reads the cursor before it starts.
        bank.queue(new SyncResult(List.of(purchase("t-2", "20.00")), List.of(), List.of(), "c2", false));
        bank.whileReadingPages(() -> store.apply(
                connectionId,
                "c1",
                List.of(new SyncResult(List.of(purchase("t-9", "99.00")), List.of(), List.of(), "c9", false))));

        IngestionService.SyncOutcome outcome = ingestion.syncNow(connectionId);

        assertThat(outcome.applied()).isFalse();
        // The winner's work stands, and the loser's older pages never overwrote it.
        assertThat(store.cursor(connectionId)).contains("c9");
        assertThat(store.entriesForUser(USER))
                .extracting(entry -> entry.transaction().externalId())
                .contains("t-9")
                .doesNotContain("t-2");
    }

    @Test
    @DisplayName("data changing mid-import starts again from where the sync began")
    void anInterruptedSyncRestartsFromTheBeginning() {
        ScriptedBank bank = new ScriptedBank(
                new SyncResult(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", true));
        bank.thenFail(new SyncInterrupted("the data changed"));
        bank.queue(new SyncResult(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());

        long connectionId = ingestion.connect(USER, "public-token").id();

        // Third request went back to the original cursor rather than continuing from c1.
        assertThat(bank.cursors).containsExactly(null, "c1", null);
        assertThat(store.entriesForUser(USER)).hasSize(1);
        assertThat(store.cursor(connectionId)).contains("c1");
    }

    @Test
    @DisplayName("a connection that will not settle gives up rather than looping forever")
    void endlessChangesEventuallyFail() {
        ScriptedBank bank = new ScriptedBank();
        for (int attempt = 0; attempt < 10; attempt++) {
            bank.thenFail(new SyncInterrupted("changed again"));
        }
        IngestionService ingestion = serviceOver(bank, TestExecutors.queueing());
        long connectionId = ingestion.connect(USER, "public-token").id();

        assertThatThrownBy(() -> ingestion.syncNow(connectionId)).isInstanceOf(SyncInterrupted.class);
        assertThat(bank.cursors).hasSize(3);
    }

    @Test
    @DisplayName("streams are only re-read when something actually changed")
    void aQuietSyncDoesNotAskForStreamsAgain() {
        ScriptedBank bank = new ScriptedBank(
                new SyncResult(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());
        long connectionId = ingestion.connect(USER, "public-token").id();
        int afterTheImport = bank.recurringCalls;

        bank.queue(new SyncResult(List.of(), List.of(), List.of(), "c1", false));
        ingestion.syncNow(connectionId);

        assertThat(afterTheImport).isEqualTo(1);
        assertThat(bank.recurringCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("a notification about a connection we do not hold is dropped, not an error")
    void anUnknownItemIsIgnored() {
        ScriptedBank bank = new ScriptedBank();
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());

        try (LogCapture logs = LogCapture.start()) {
            ingestion.onWebhook(new BankWebhook("item-nobody-knows", BankWebhook.Action.SYNC_TRANSACTIONS));

            assertThat(logs.everything()).contains("item-nobody-knows");
        }
        assertThat(bank.cursors).isEmpty();
    }

    @Test
    @DisplayName("a notification asking only for streams does not re-read transactions")
    void recurringRefreshIsItsOwnPath() {
        ScriptedBank bank = new ScriptedBank(
                new SyncResult(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());
        ingestion.connect(USER, "public-token");
        int requestsSoFar = bank.cursors.size();

        ingestion.onWebhook(new BankWebhook(ITEM, BankWebhook.Action.REFRESH_RECURRING));

        assertThat(bank.cursors).hasSize(requestsSoFar);
        assertThat(bank.recurringCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("a background failure is logged rather than thrown into the void")
    void backgroundWorkReportsItsOwnFailures() {
        ScriptedBank bank = new ScriptedBank();
        bank.thenFail(new IllegalStateException("the bank is having a bad day"));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());

        try (LogCapture logs = LogCapture.start()) {
            // Nothing is waiting on this, so it must not escape - but it must be findable.
            ingestion.connect(USER, "public-token");

            assertThat(logs.everything()).contains("Background sync");
        }
    }

    private IngestionService serviceOver(ScriptedBank bank, Executor executor) {
        return new IngestionService(bank, bank, bank, store, store, cipher, executor);
    }

    private static NormalisedTransaction purchase(String id, String amount) {
        return new NormalisedTransaction(
                id,
                "acc-1",
                LocalDate.of(2026, 9, 15),
                Money.of(amount),
                "Somewhere",
                "merchant-1",
                null,
                null,
                Classification.spend(SpendCategory.GROCERIES),
                false);
    }

    private static String aKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /** A bank that answers from a script, so a sequence a real one rarely produces can be tested. */
    private static final class ScriptedBank
            implements BankDataProvider, BankLinkProvider, RecurringStreamProvider {

        private final List<Object> answers = new ArrayList<>();
        private final List<String> cursors = new ArrayList<>();
        private int recurringCalls;
        private int next;
        private Runnable duringTheNextRead;

        private ScriptedBank(SyncResult... pages) {
            answers.addAll(List.of(pages));
        }

        void queue(SyncResult page) {
            answers.add(page);
        }

        void thenFail(RuntimeException failure) {
            answers.add(failure);
        }

        /** Runs once, the next time pages are being read, standing in for a concurrent sync. */
        void whileReadingPages(Runnable competingWork) {
            this.duringTheNextRead = competingWork;
        }

        @Override
        public String createLinkToken(String userId) {
            return "link-token";
        }

        @Override
        public String itemIdFor(String accessToken) {
            return ITEM;
        }

        @Override
        public String exchangePublicToken(String publicToken) {
            return "access-token";
        }

        @Override
        public SyncResult sync(String accessToken, String cursorOrNull) {
            cursors.add(cursorOrNull);
            if (duringTheNextRead != null) {
                Runnable competing = duringTheNextRead;
                duringTheNextRead = null;
                competing.run();
            }
            if (next >= answers.size()) {
                return new SyncResult(List.of(), List.of(), List.of(), cursorOrNull, false);
            }
            Object answer = answers.get(next++);
            if (answer instanceof RuntimeException failure) {
                throw failure;
            }
            return (SyncResult) answer;
        }

        @Override
        public List<RecurringStream> recurringStreams(String accessToken) {
            recurringCalls++;
            return List.of();
        }
    }
}
