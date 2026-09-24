package com.hasan.budget.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.RejectedExecutionException;
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
                SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", true),
                SyncResult.of(List.of(purchase("t-2", "20.00")), List.of(), List.of(), "c2", false));
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
                SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());
        long connectionId = ingestion.connect(USER, "public-token").id();

        // A second sync finishes while this one is still reading its pages - the only moment the
        // race is real, since each attempt re-reads the cursor before it starts.
        bank.queue(SyncResult.of(List.of(purchase("t-2", "20.00")), List.of(), List.of(), "c2", false));
        bank.whileReadingPages(() -> store.apply(
                connectionId,
                "c1",
                List.of(SyncResult.of(List.of(purchase("t-9", "99.00")), List.of(), List.of(), "c9", false))));

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
                SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", true));
        bank.thenFail(new SyncInterrupted("the data changed"));
        bank.queue(SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
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
                SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());
        long connectionId = ingestion.connect(USER, "public-token").id();
        int afterTheImport = bank.recurringCalls;

        bank.queue(SyncResult.of(List.of(), List.of(), List.of(), "c1", false));
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
                SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());
        ingestion.connect(USER, "public-token");
        int requestsSoFar = bank.cursors.size();

        ingestion.onWebhook(new BankWebhook(ITEM, BankWebhook.Action.REFRESH_RECURRING));

        assertThat(bank.cursors).hasSize(requestsSoFar);
        assertThat(bank.recurringCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("an import still counts when the bank cannot list repeating payments yet")
    void streamsFailingDoesNotUndoASuccessfulImport() {
        // The ordinary case just after linking: transactions are ready, stream detection is not, and
        // the provider answers that request with an error. The transactions are already stored by
        // then, so failing the sync would report a disaster that did not happen.
        ScriptedBank bank = new ScriptedBank(
                SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        bank.refuseStreams(new IllegalStateException("PRODUCT_NOT_READY"));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());

        long connectionId = ingestion.connect(USER, "public-token").id();
        IngestionService.SyncOutcome outcome;
        try (LogCapture logs = LogCapture.start()) {
            bank.queue(SyncResult.of(List.of(purchase("t-2", "20.00")), List.of(), List.of(), "c2", false));
            outcome = ingestion.syncNow(connectionId);

            assertThat(logs.everything()).contains("repeating payments could not be read");
        }

        assertThat(outcome.applied()).isTrue();
        assertThat(store.entriesForUser(USER)).hasSize(2);
        assertThat(store.cursor(connectionId)).contains("c2");
    }

    @Test
    @DisplayName("a bank that never stops offering pages is cut off rather than filling memory")
    void endlessPaginationIsBounded() {
        ScriptedBank bank = new ScriptedBank();
        bank.alwaysAnswer(SyncResult.of(List.of(purchase("t-1", "1.00")), List.of(), List.of(), "c", true));
        IngestionService ingestion = serviceOver(bank, TestExecutors.queueing());
        long connectionId = ingestion.connect(USER, "public-token").id();

        assertThatThrownBy(() -> ingestion.syncNow(connectionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pages of transactions");
        assertThat(store.entriesForUser(USER)).isEmpty();
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

    @Test
    @DisplayName("a connection shows as importing until its first sync finishes, then as up to date")
    void aNewConnectionSaysItIsStillImporting() {
        ScriptedBank bank = new ScriptedBank(
                SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        TestExecutors.Queueing executor = TestExecutors.queueing();
        IngestionService ingestion = serviceOver(bank, executor);

        ingestion.connect(USER, "public-token");
        assertThat(ingestion.connectionsFor(USER)).singleElement().satisfies(status -> assertThat(status.hasImported())
                .isFalse());

        executor.runQueuedWork();
        assertThat(ingestion.connectionsFor(USER)).singleElement().satisfies(status -> assertThat(status.hasImported())
                .isTrue());
    }

    @Test
    @DisplayName("a provider failure while connecting reaches the user as a sentence, never as the provider's error")
    void aFailedConnectionExplainsItself() {
        ScriptedBank bank = new ScriptedBank();
        bank.refuseExchanging(new IllegalStateException("INVALID_API_KEYS for client 5f3c secret abc123"));
        IngestionService ingestion = serviceOver(bank, TestExecutors.queueing());

        assertThatThrownBy(() -> ingestion.connect(USER, "public-token"))
                .isInstanceOf(BankUnavailableException.class)
                .hasMessage("Your bank said yes, but we could not finish connecting it. Try connecting again.")
                .hasMessageNotContaining("abc123");
        assertThat(store.forUser(USER)).isEmpty();
    }

    @Test
    @DisplayName("refreshing asks every one of this user's banks, and nobody else's, without waiting")
    void refreshingAsksOnlyThisUsersBanks() {
        ScriptedBank bank = new ScriptedBank();
        TestExecutors.Queueing executor = TestExecutors.queueing();
        IngestionService ingestion = serviceOver(bank, executor);
        ingestion.connect(USER, "first-bank");
        ingestion.connect(USER, "second-bank");
        ingestion.connect("someone-else", "their-bank");
        executor.runQueuedWork();

        int asked = ingestion.requestSyncFor(USER);

        assertThat(asked).isEqualTo(2);
        assertThat(executor.pending()).isEqualTo(2);
    }

    @Test
    @DisplayName("the scheduled refresh asks for every connection there is")
    void theScheduleRefreshesEveryone() {
        ScriptedBank bank = new ScriptedBank();
        TestExecutors.Queueing executor = TestExecutors.queueing();
        IngestionService ingestion = serviceOver(bank, executor);
        ingestion.connect(USER, "first-bank");
        ingestion.connect("someone-else", "their-bank");
        executor.runQueuedWork();

        new BankRefreshSchedule(ingestion).refreshEveryConnection();

        assertThat(executor.pending()).isEqualTo(2);
    }

    @Test
    @DisplayName("a full refresh queue stops the scheduled run rather than failing it")
    void aFullQueueDefersTheRest() {
        ScriptedBank bank = new ScriptedBank();
        TestExecutors.Queueing setup = TestExecutors.queueing();
        IngestionService connecting = serviceOver(bank, setup);
        connecting.connect(USER, "first-bank");
        connecting.connect(USER, "second-bank");

        IngestionService full = serviceOver(bank, work -> {
            throw new RejectedExecutionException("queue full");
        });

        try (LogCapture logs = LogCapture.start()) {
            full.requestSyncOfEveryConnection();
            assertThat(logs.everything()).contains("queue is full after 0 of 2");
        }
    }

    @Test
    @DisplayName("disconnecting revokes the credential and leaves nothing of that bank behind")
    void disconnectingForgetsTheBank() {
        ScriptedBank bank = new ScriptedBank(
                SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());
        long connectionId = ingestion.connect(USER, "public-token").id();
        assertThat(store.entriesForUser(USER)).isNotEmpty();

        assertThat(ingestion.disconnect(USER, connectionId)).isTrue();

        assertThat(bank.revoked).containsExactly("access-public-token");
        assertThat(ingestion.connectionsFor(USER)).isEmpty();
        assertThat(store.entriesForUser(USER)).isEmpty();
        assertThat(store.cursor(connectionId)).isEmpty();
    }

    @Test
    @DisplayName("nobody can disconnect a bank that is not theirs")
    void disconnectingSomeoneElsesBankDoesNothing() {
        ScriptedBank bank = new ScriptedBank(
                SyncResult.of(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", false));
        IngestionService ingestion = serviceOver(bank, TestExecutors.immediate());
        long theirs = ingestion.connect(USER, "public-token").id();

        assertThat(ingestion.disconnect("someone-else", theirs)).isFalse();

        assertThat(bank.revoked).isEmpty();
        assertThat(ingestion.connectionsFor(USER)).hasSize(1);
        assertThat(store.entriesForUser(USER)).isNotEmpty();
    }

    @Test
    @DisplayName("a bank is forgotten even when the provider cannot be told")
    void disconnectingSurvivesAnUnreachableProvider() {
        ScriptedBank bank = new ScriptedBank();
        IngestionService ingestion = serviceOver(bank, TestExecutors.queueing());
        long connectionId = ingestion.connect(USER, "public-token").id();
        bank.refuseRevoking(new IllegalStateException("provider unreachable"));

        assertThat(ingestion.disconnect(USER, connectionId)).isTrue();
        assertThat(ingestion.connectionsFor(USER)).isEmpty();
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
        private RuntimeException streamFailure;
        private SyncResult standingAnswer;

        private ScriptedBank(SyncResult... pages) {
            answers.addAll(List.of(pages));
        }

        void queue(SyncResult page) {
            answers.add(page);
        }

        /** Makes stream detection fail, as a provider does before it has finished detecting any. */
        void refuseStreams(RuntimeException failure) {
            this.streamFailure = failure;
        }

        /** Answers every request the same way, for testing what happens when a bank never stops. */
        void alwaysAnswer(SyncResult page) {
            this.standingAnswer = page;
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
            // One connection per public token, so a test can hold several - and the first is the one
            // the webhook tests name.
            return accessToken.equals("access-public-token") ? ITEM : "item-for-" + accessToken;
        }

        private final List<String> revoked = new ArrayList<>();
        private RuntimeException revokeFailure;
        private RuntimeException exchangeFailure;

        /** Makes finishing a connection fail, as it does when the provider rejects our keys. */
        void refuseExchanging(RuntimeException failure) {
            this.exchangeFailure = failure;
        }

        /** Makes revoking fail, as it does when the provider is unreachable. */
        void refuseRevoking(RuntimeException failure) {
            this.revokeFailure = failure;
        }

        @Override
        public void revoke(String accessToken) {
            if (revokeFailure != null) {
                throw revokeFailure;
            }
            revoked.add(accessToken);
        }

        @Override
        public String exchangePublicToken(String publicToken) {
            if (exchangeFailure != null) {
                throw exchangeFailure;
            }
            return "access-" + publicToken;
        }

        @Override
        public SyncResult sync(String accessToken, String cursorOrNull) {
            cursors.add(cursorOrNull);
            if (duringTheNextRead != null) {
                Runnable competing = duringTheNextRead;
                duringTheNextRead = null;
                competing.run();
            }
            if (standingAnswer != null) {
                return standingAnswer;
            }
            if (next >= answers.size()) {
                return SyncResult.of(List.of(), List.of(), List.of(), cursorOrNull, false);
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
            if (streamFailure != null) {
                throw streamFailure;
            }
            return List.of();
        }
    }
}
