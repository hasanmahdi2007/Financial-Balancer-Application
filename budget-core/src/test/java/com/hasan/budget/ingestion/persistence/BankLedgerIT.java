package com.hasan.budget.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.ingestion.domain.BankConnection;
import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.ingestion.domain.EncryptedToken;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.port.BankConnections;
import com.hasan.budget.ingestion.port.BankLedger;
import com.hasan.budget.ingestion.support.BankLedgerContract;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The store against the database it actually ships with.
 *
 * <p>It runs the same contract as the in-memory store the fast tests are built on, so the
 * properties those tests rely on - idempotent writes, a compare-and-set cursor, deletions scoped to
 * one connection - are shown to hold here too rather than only in a fake. The extra cases below are
 * the ones only a real database can answer: that the migration applies from empty, that the schema
 * refuses a row the domain would consider impossible, and that a credential is stored encrypted.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({JdbcBankConnections.class, JdbcBankLedger.class})
@Testcontainers
class BankLedgerIT extends BankLedgerContract {

    // The image the compose file ships, so a difference here can never be a difference in database.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine");

    private static final AtomicInteger USERS = new AtomicInteger();

    @Autowired
    private JdbcBankConnections connections;

    @Autowired
    private JdbcBankLedger ledger;

    @Autowired
    private JdbcClient jdbc;

    @Override
    protected BankConnections connections() {
        return connections;
    }

    @Override
    protected BankLedger ledger() {
        return ledger;
    }

    @Override
    protected String someUserId() {
        return "user-" + USERS.incrementAndGet();
    }

    @Test
    @DisplayName("the access token is stored as ciphertext and nothing else")
    void theCredentialColumnHoldsNoPlaintext() {
        String userId = someUserId();
        connections().connect(userId, "item-secret", new EncryptedToken("v1:c2VhbGVk"));

        String stored = jdbc.sql(
                        "SELECT access_token_ciphertext FROM bank_connection WHERE provider_item_id = 'item-secret'")
                .query(String.class)
                .single();

        assertThat(stored).isEqualTo("v1:c2VhbGVk").startsWith("v1:");
    }

    @Test
    @DisplayName("the database refuses a transfer that carries a spending category")
    void theTwoAxesAreEnforcedInTheSchema() {
        BankConnection connection = connect(someUserId(), "item-constraint");

        // The domain makes this unrepresentable; the schema makes it unstorable, so a future writer
        // that bypasses the domain cannot quietly turn a transfer into spending.
        assertThatThrownBy(() -> jdbc.sql(
                                """
                                INSERT INTO bank_transaction (connection_id, external_id, account_id, txn_date,
                                                              amount, kind, category, pending)
                                VALUES (:id, 't-bad', 'acc', DATE '2026-09-15', 10.00,
                                        'TRANSFER_INTERNAL', 'GROCERIES', false)
                                """)
                        .param("id", connection.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("amounts and locations survive the round trip exactly")
    void nothingIsLostOnTheWayToTheDatabaseAndBack() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-roundtrip");
        NormalisedTransaction original = new NormalisedTransaction(
                "t-round",
                "acc-1",
                LocalDate.of(2026, 9, 15),
                Money.of("-810.00"),
                "Sweetgreen",
                "merchant-sweetgreen",
                36.299545,
                -86.695992,
                Classification.spend(SpendCategory.DINING_OUT),
                true);

        ledger().apply(connection.id(), null, List.of(
                SyncResult.of(List.of(original), List.of(), List.of(), "cursor-1", false)));

        assertThat(ledger().entriesForUser(userId))
                .singleElement()
                .satisfies(entry -> assertThat(entry.transaction()).isEqualTo(original));
    }

    @Test
    @DisplayName("a transaction with no location is read back without one, not at latitude zero")
    void absentCoordinatesStayAbsent() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-nowhere");
        NormalisedTransaction unlocated = new NormalisedTransaction(
                "t-nowhere",
                "acc-1",
                LocalDate.of(2026, 9, 15),
                Money.of("12.00"),
                null,
                null,
                null,
                null,
                Classification.notSpending(TransactionKind.TRANSFER_INTERNAL),
                false);

        ledger().apply(connection.id(), null, List.of(
                SyncResult.of(List.of(unlocated), List.of(), List.of(), "cursor-1", false)));

        assertThat(ledger().entriesForUser(userId))
                .singleElement()
                .satisfies(entry -> {
                    // getDouble returns 0.0 for NULL, which would place every such row off Africa.
                    assertThat(entry.transaction().latitude()).isNull();
                    assertThat(entry.transaction().longitude()).isNull();
                });
    }

    @Test
    @DisplayName("removing a connection takes its transactions and streams with it")
    void disconnectingLeavesNothingBehind() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-cascade");
        ledger().apply(connection.id(), null, List.of(page("cursor-1", purchase("t-1", "12.34"))));
        ledger().replaceStreams(connection.id(), List.of(stream("s-1", "Netflix")));

        jdbc.sql("DELETE FROM bank_connection WHERE id = :id")
                .param("id", connection.id())
                .update();

        assertThat(ledger().entriesForUser(userId)).isEmpty();
        assertThat(ledger().streamsForUser(userId)).isEmpty();
        // Scoped to this connection: other cases in this class use the same stream identifier, and a
        // count across all of them would be answering a different question.
        assertThat(jdbc.sql("SELECT count(*) FROM recurring_stream_member WHERE connection_id = :id")
                        .param("id", connection.id())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a sync records when it happened, so a stale connection can be spotted")
    void syncingStampsTheConnection() {
        String userId = someUserId();
        BankConnection connection = connect(userId, "item-stamped");

        ledger().apply(connection.id(), null, List.of(page("cursor-1", purchase("t-1", "12.34"))));

        assertThat(jdbc.sql("SELECT last_synced_at FROM bank_connection WHERE id = :id")
                        .param("id", connection.id())
                        .query(java.time.OffsetDateTime.class)
                        .single())
                .isNotNull();
    }
}
