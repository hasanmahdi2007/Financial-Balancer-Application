package com.hasan.budget.ingestion.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.ingestion.domain.BankConnection;
import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.ingestion.domain.EncryptedToken;
import com.hasan.budget.ingestion.domain.Frequency;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.ingestion.domain.RecurringStream;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.port.BankConnections;
import com.hasan.budget.ingestion.port.BankLedger;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What any store of bank data has to do, run against every implementation there is.
 *
 * <p>The fast tests build their world on the in-memory store, so every guarantee they rely on has to
 * be shown to hold in Postgres too. Without this, a fake that is merely convenient would let a whole
 * suite pass while the real thing duplicated a user's spending on the second delivery of a webhook.
 */
public abstract class BankLedgerContract {

    protected abstract BankConnections connections();

    protected abstract BankLedger ledger();

    /** A distinct user per test, so implementations backed by a shared database do not collide. */
    protected abstract String someUserId();

    @Test
    @DisplayName("a connection that has never synced asks for the whole history")
    void aNewConnectionHasNoCursor() {
        BankConnection connection = connect(someUserId(), "item-new");

        assertThat(ledger().cursor(connection.id())).isEmpty();
    }

    @Test
    @DisplayName("applying a sync stores its transactions and remembers where it finished")
    void applyingASyncStoresTransactionsAndAdvancesTheCursor() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-apply");

        boolean applied = ledger().apply(connection.id(), null, List.of(page("cursor-1", purchase("t-1", "12.34"))));

        assertThat(applied).isTrue();
        assertThat(ledger().cursor(connection.id())).contains("cursor-1");
        assertThat(ledger().entriesForUser(userId)).hasSize(1);
    }

    @Test
    @DisplayName("the same page applied twice leaves exactly one transaction")
    void applyingTheSamePageTwiceChangesNothing() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-idempotent");
        SyncResult page = page("cursor-1", purchase("t-1", "12.34"));

        ledger().apply(connection.id(), null, List.of(page));
        // The second delivery starts from the cursor the first one left, as a real retry would.
        ledger().apply(connection.id(), "cursor-1", List.of(page));

        assertThat(ledger().entriesForUser(userId)).hasSize(1);
    }

    @Test
    @DisplayName("a sync that started from an older cursor writes nothing")
    void aSyncThatHasBeenOvertakenIsRefused() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-race");
        ledger().apply(connection.id(), null, List.of(page("cursor-1", purchase("t-1", "12.34"))));

        boolean applied = ledger().apply(connection.id(), null, List.of(page("cursor-2", purchase("t-2", "99.99"))));

        assertThat(applied).isFalse();
        assertThat(ledger().entriesForUser(userId)).hasSize(1);
        assertThat(ledger().cursor(connection.id())).contains("cursor-1");
    }

    @Test
    @DisplayName("a modified transaction replaces the one it supersedes rather than joining it")
    void aModifiedTransactionReplacesTheOriginal() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-modify");
        ledger().apply(connection.id(), null, List.of(page("cursor-1", purchase("t-1", "12.34"))));

        SyncResult correction = new SyncResult(
                List.of(), List.of(purchase("t-1", "18.00")), List.of(), "cursor-2", false);
        ledger().apply(connection.id(), "cursor-1", List.of(correction));

        assertThat(ledger().entriesForUser(userId)).hasSize(1);
        assertThat(ledger().entriesForUser(userId).get(0).transaction().amount())
                .isEqualTo(Money.of("18.00"));
    }

    @Test
    @DisplayName("a removed transaction disappears")
    void aRemovedTransactionIsDeleted() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-remove");
        ledger().apply(connection.id(), null, List.of(page("cursor-1", purchase("t-1", "12.34"))));

        SyncResult retraction = new SyncResult(List.of(), List.of(), List.of("t-1"), "cursor-2", false);
        ledger().apply(connection.id(), "cursor-1", List.of(retraction));

        assertThat(ledger().entriesForUser(userId)).isEmpty();
    }

    @Test
    @DisplayName("every page of one sync is applied, in order")
    void allPagesOfOneSyncAreApplied() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-pages");

        ledger().apply(
                        connection.id(),
                        null,
                        List.of(
                                new SyncResult(List.of(purchase("t-1", "10.00")), List.of(), List.of(), "c1", true),
                                new SyncResult(List.of(purchase("t-2", "20.00")), List.of(), List.of("t-1"), "c2", false)));

        assertThat(ledger().entriesForUser(userId))
                .singleElement()
                .satisfies(entry -> assertThat(entry.transaction().externalId()).isEqualTo("t-2"));
        assertThat(ledger().cursor(connection.id())).contains("c2");
    }

    @Test
    @DisplayName("one user's transactions are never visible to another")
    void transactionsAreScopedToTheirOwner() {
        String owner = someUserId();
        String stranger = someUserId();
        BankConnection connection = connect(owner, "item-private");
        ledger().apply(connection.id(), null, List.of(page("cursor-1", purchase("t-1", "12.34"))));

        assertThat(ledger().entriesForUser(stranger)).isEmpty();
    }

    @Test
    @DisplayName("re-linking the same bank keeps the history and the cursor")
    void reconnectingReplacesTheCredentialWithoutLosingAnything() {
        String userId = someUserId();
        BankConnection first = connect(userId, "item-relink");
        ledger().apply(first.id(), null, List.of(page("cursor-1", purchase("t-1", "12.34"))));

        BankConnection again = connections().connect(userId, "item-relink", new EncryptedToken("v1:refreshed"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.accessToken().value()).isEqualTo("v1:refreshed");
        assertThat(ledger().cursor(first.id())).contains("cursor-1");
        assertThat(ledger().entriesForUser(userId)).hasSize(1);
    }

    @Test
    @DisplayName("a bank already connected to someone else is refused rather than taken over")
    void aConnectionCannotBeStolenByAnotherAccount() {
        String owner = someUserId();
        String stranger = someUserId();
        connect(owner, "item-contested");

        assertThatThrownBy(() -> connections().connect(stranger, "item-contested", new EncryptedToken("v1:theirs")))
                .isInstanceOf(RuntimeException.class);
        assertThat(connections().findByProviderItemId("item-contested"))
                .get()
                .satisfies(connection -> assertThat(connection.userId()).isEqualTo(owner));
    }

    @Test
    @DisplayName("streams are replaced wholesale, so a cancelled subscription stops being one")
    void replacingStreamsRemovesTheOnesThatAreGone() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-streams");
        ledger().replaceStreams(connection.id(), List.of(stream("s-1", "Netflix"), stream("s-2", "Spotify")));

        ledger().replaceStreams(connection.id(), List.of(stream("s-1", "Netflix")));

        assertThat(ledger().streamsForUser(userId))
                .singleElement()
                .satisfies(stream -> {
                    assertThat(stream.streamId()).isEqualTo("s-1");
                    assertThat(stream.memberIds()).containsExactly("t-1");
                });
    }

    @Test
    @DisplayName("a webhook cannot delete another connection's transactions")
    void removalIsScopedToItsOwnConnection() {
        String userId = someUserId();
        BankConnection mine = connect(userId, "item-mine");
        BankConnection other = connect(userId, "item-other");
        ledger().apply(mine.id(), null, List.of(page("cursor-1", purchase("t-shared", "12.34"))));
        ledger().apply(other.id(), null, List.of(page("cursor-1", purchase("t-other", "50.00"))));

        ledger().apply(other.id(), "cursor-1", List.of(
                new SyncResult(List.of(), List.of(), List.of("t-shared"), "cursor-2", false)));

        assertThat(ledger().entriesForUser(userId))
                .extracting(entry -> entry.transaction().externalId())
                .contains("t-shared");
    }

    protected BankConnection connect(String userId, String providerItemId) {
        return connections().connect(userId, providerItemId, new EncryptedToken("v1:ciphertext"));
    }

    protected static SyncResult page(String nextCursor, NormalisedTransaction... added) {
        return new SyncResult(List.of(added), List.of(), List.of(), nextCursor, false);
    }

    protected static NormalisedTransaction purchase(String externalId, String amount) {
        return new NormalisedTransaction(
                externalId,
                "account-1",
                LocalDate.of(2026, 9, 14),
                Money.of(amount),
                "Bojangles",
                "merchant-1",
                35.2271,
                -80.8431,
                Classification.spend(SpendCategory.DINING_OUT),
                false);
    }

    protected static NormalisedTransaction transfer(String externalId, String accountId, String amount) {
        return new NormalisedTransaction(
                externalId,
                accountId,
                LocalDate.of(2026, 9, 14),
                Money.of(amount),
                null,
                null,
                null,
                null,
                Classification.notSpending(TransactionKind.TRANSFER_INTERNAL),
                false);
    }

    protected static RecurringStream stream(String streamId, String label) {
        return new RecurringStream(
                streamId,
                "account-1",
                RecurringStream.Direction.MONEY_OUT,
                label,
                Frequency.MONTHLY,
                Money.of("19.57"),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 1),
                true,
                List.of("t-1"));
    }
}
