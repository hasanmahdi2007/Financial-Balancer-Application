package com.hasan.budget.ingestion.persistence;

import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.ingestion.domain.Frequency;
import com.hasan.budget.ingestion.domain.AccountSnapshot;
import com.hasan.budget.ingestion.domain.LedgerEntry;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.ingestion.domain.RecurringStream;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.port.BankLedger;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Imported transactions and detected streams, in Postgres. */
@Repository
public class JdbcBankLedger implements BankLedger {

    private final JdbcClient jdbc;

    public JdbcBankLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> cursor(long connectionId) {
        return jdbc.sql("SELECT sync_cursor FROM bank_connection WHERE id = :id")
                .param("id", connectionId)
                .query(String.class)
                .optional();
    }

    /**
     * Writes a whole sync, or nothing.
     *
     * <p>The cursor is moved <strong>first</strong>, and that ordering is doing two jobs at once.
     * The conditional update is a compare-and-set: if another sync has already advanced this
     * connection, it matches no row, the method reports that it wrote nothing, and pages describing
     * an older state of the world never reach the table. The same statement also takes a lock on the
     * connection row for the rest of the transaction, so a second sync arriving mid-write waits and
     * then finds the cursor changed, rather than interleaving its rows with these.
     *
     * <p>{@code IS NOT DISTINCT FROM} rather than {@code =} because the expected cursor is NULL on a
     * first import, and NULL = NULL is not true in SQL - the comparison every equality-based version
     * of this gets wrong on exactly the first sync.
     */
    @Override
    @Transactional
    public boolean apply(long connectionId, String expectedCursor, List<SyncResult> pages) {
        if (pages.isEmpty()) {
            return true;
        }
        String finalCursor = pages.get(pages.size() - 1).nextCursor();
        int advanced = jdbc.sql(
                        """
                        UPDATE bank_connection
                           SET sync_cursor = :next, last_synced_at = now()
                         WHERE id = :id
                           AND sync_cursor IS NOT DISTINCT FROM :expected
                        """)
                .param("next", finalCursor)
                .param("id", connectionId)
                .param("expected", expectedCursor)
                .update();
        if (advanced == 0) {
            return false;
        }
        // In order: a later page may remove what an earlier one added, and the end state is the truth.
        for (SyncResult page : pages) {
            page.added().forEach(transaction -> upsert(connectionId, transaction));
            page.modified().forEach(transaction -> upsert(connectionId, transaction));
            page.removedExternalIds().forEach(externalId -> remove(connectionId, externalId));
        }
        // Balances come from the last page: every page of one sync reports the same accounts, and
        // the last is the freshest reading of them.
        pages.get(pages.size() - 1).accounts().forEach(account -> upsertAccount(connectionId, account));
        return true;
    }

    /**
     * Written by identity, so applying the same page twice leaves exactly what it left the first
     * time. This is the whole of webhook idempotency: nothing counts deliveries, because nothing
     * needs to.
     */
    private void upsert(long connectionId, NormalisedTransaction transaction) {
        Classification classification = transaction.classification();
        jdbc.sql(
                        """
                        INSERT INTO bank_transaction (connection_id, external_id, account_id, txn_date, amount,
                                                      merchant_name, merchant_entity_id, latitude, longitude,
                                                      kind, category, pending, towards_savings)
                        VALUES (:connectionId, :externalId, :accountId, :date, :amount, :merchantName,
                                :merchantEntityId, :latitude, :longitude, :kind, :category, :pending, :towardsSavings)
                        ON CONFLICT (connection_id, external_id) DO UPDATE SET
                            account_id = EXCLUDED.account_id,
                            txn_date = EXCLUDED.txn_date,
                            amount = EXCLUDED.amount,
                            merchant_name = EXCLUDED.merchant_name,
                            merchant_entity_id = EXCLUDED.merchant_entity_id,
                            latitude = EXCLUDED.latitude,
                            longitude = EXCLUDED.longitude,
                            kind = EXCLUDED.kind,
                            category = EXCLUDED.category,
                            pending = EXCLUDED.pending,
                            towards_savings = EXCLUDED.towards_savings
                        """)
                .param("connectionId", connectionId)
                .param("externalId", transaction.externalId())
                .param("accountId", transaction.accountId())
                .param("date", transaction.date())
                .param("amount", transaction.amount().amount())
                .param("merchantName", transaction.merchantName())
                .param("merchantEntityId", transaction.merchantEntityId())
                .param("latitude", transaction.latitude())
                .param("longitude", transaction.longitude())
                .param("kind", classification.kind().name())
                .param("category", classification.category() == null ? null : classification.category().name())
                .param("pending", transaction.pending())
                .param("towardsSavings", classification.towardsSavings())
                .update();
    }

    /**
     * The latest reading of one account, replacing whatever the last sync recorded.
     *
     * <p>Replaced rather than appended: this table answers "how much is there now", and a history
     * of balances would be a different feature with a different shape.
     */
    private void upsertAccount(long connectionId, AccountSnapshot account) {
        jdbc.sql(
                        """
                        INSERT INTO bank_account (connection_id, account_id, label, role,
                                                  current_balance, available_balance, observed_at)
                        VALUES (:connectionId, :accountId, :label, :role, :current, :available, now())
                        ON CONFLICT (connection_id, account_id) DO UPDATE SET
                            label = EXCLUDED.label,
                            role = EXCLUDED.role,
                            current_balance = EXCLUDED.current_balance,
                            available_balance = EXCLUDED.available_balance,
                            observed_at = EXCLUDED.observed_at
                        """)
                .param("connectionId", connectionId)
                .param("accountId", account.accountId())
                .param("label", account.label())
                .param("role", account.role().name())
                .param("current", account.current().amount())
                .param("available", account.available() == null ? null : account.available().amount())
                .update();
    }

    /** Scoped to the connection, so an identifier from anywhere else cannot delete a user's spending. */
    private void remove(long connectionId, String externalId) {
        jdbc.sql("DELETE FROM bank_transaction WHERE connection_id = :connectionId AND external_id = :externalId")
                .param("connectionId", connectionId)
                .param("externalId", externalId)
                .update();
    }

    @Override
    @Transactional
    public void replaceStreams(long connectionId, List<RecurringStream> streams) {
        // Members go with their stream through the cascade; a cancelled subscription has to vanish
        // rather than linger as a commitment nobody is paying any more.
        jdbc.sql("DELETE FROM recurring_stream WHERE connection_id = :connectionId")
                .param("connectionId", connectionId)
                .update();
        for (RecurringStream stream : streams) {
            jdbc.sql(
                            """
                            INSERT INTO recurring_stream (connection_id, stream_id, account_id, direction, label,
                                                          frequency, last_amount, last_date, next_expected, active)
                            VALUES (:connectionId, :streamId, :accountId, :direction, :label, :frequency,
                                    :lastAmount, :lastDate, :nextExpected, :active)
                            """)
                    .param("connectionId", connectionId)
                    .param("streamId", stream.streamId())
                    .param("accountId", stream.accountId())
                    .param("direction", stream.direction().name())
                    .param("label", stream.label())
                    .param("frequency", stream.frequency().name())
                    .param("lastAmount", stream.lastAmount().amount())
                    .param("lastDate", stream.lastDate())
                    .param("nextExpected", stream.nextExpected())
                    .param("active", stream.active())
                    .update();
            for (String memberId : stream.memberIds()) {
                jdbc.sql(
                                """
                                INSERT INTO recurring_stream_member (connection_id, stream_id, external_id)
                                VALUES (:connectionId, :streamId, :externalId)
                                ON CONFLICT DO NOTHING
                                """)
                        .param("connectionId", connectionId)
                        .param("streamId", stream.streamId())
                        .param("externalId", memberId)
                        .update();
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntry> entriesForUser(String userId) {
        return jdbc.sql(
                        """
                        SELECT t.connection_id, t.external_id, t.account_id, t.txn_date, t.amount, t.merchant_name,
                               t.merchant_entity_id, t.latitude, t.longitude, t.kind, t.category, t.pending, t.towards_savings
                          FROM bank_transaction t
                          JOIN bank_connection c ON c.id = t.connection_id
                         WHERE c.user_id = :userId
                         ORDER BY t.txn_date, t.external_id
                        """)
                .param("userId", userId)
                .query(JdbcBankLedger::readEntry)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecurringStream> streamsForUser(String userId) {
        return jdbc.sql(
                        """
                        SELECT s.connection_id, s.stream_id, s.account_id, s.direction, s.label, s.frequency,
                               s.last_amount, s.last_date, s.next_expected, s.active,
                               COALESCE(ARRAY_AGG(m.external_id) FILTER (WHERE m.external_id IS NOT NULL),
                                        ARRAY[]::text[]) AS member_ids
                          FROM recurring_stream s
                          JOIN bank_connection c ON c.id = s.connection_id
                          LEFT JOIN recurring_stream_member m
                                 ON m.connection_id = s.connection_id AND m.stream_id = s.stream_id
                         WHERE c.user_id = :userId
                         GROUP BY s.connection_id, s.stream_id, s.account_id, s.direction, s.label, s.frequency,
                                  s.last_amount, s.last_date, s.next_expected, s.active
                         ORDER BY s.stream_id
                        """)
                .param("userId", userId)
                .query(JdbcBankLedger::readStream)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountSnapshot> accountsForUser(String userId) {
        return jdbc.sql(
                        """
                        SELECT a.account_id, a.label, a.role, a.current_balance, a.available_balance
                          FROM bank_account a
                          JOIN bank_connection c ON c.id = a.connection_id
                         WHERE c.user_id = :userId
                         ORDER BY a.role, a.label
                        """)
                .param("userId", userId)
                .query(JdbcBankLedger::readAccount)
                .list();
    }

    private static AccountSnapshot readAccount(ResultSet rs, int row) throws SQLException {
        java.math.BigDecimal available = rs.getBigDecimal("available_balance");
        return new AccountSnapshot(
                rs.getString("account_id"),
                rs.getString("label"),
                com.hasan.budget.ingestion.domain.AccountRole.valueOf(rs.getString("role")),
                new Money(rs.getBigDecimal("current_balance")),
                // Null rather than zero: a bank that reports no available balance has not told us it
                // is empty, and zero would read as an account with nothing in it.
                available == null ? null : new Money(available));
    }

    private static LedgerEntry readEntry(ResultSet rs, int row) throws SQLException {
        Classification classification = readClassification(rs);
        return new LedgerEntry(
                rs.getLong("connection_id"),
                new NormalisedTransaction(
                        rs.getString("external_id"),
                        rs.getString("account_id"),
                        rs.getObject("txn_date", LocalDate.class),
                        new Money(rs.getBigDecimal("amount")),
                        rs.getString("merchant_name"),
                        rs.getString("merchant_entity_id"),
                        nullableDouble(rs, "latitude"),
                        nullableDouble(rs, "longitude"),
                        classification,
                        rs.getBoolean("pending")));
    }

    private static RecurringStream readStream(ResultSet rs, int row) throws SQLException {
        String[] memberIds = (String[]) rs.getArray("member_ids").getArray();
        return new RecurringStream(
                rs.getString("stream_id"),
                rs.getString("account_id"),
                RecurringStream.Direction.valueOf(rs.getString("direction")),
                rs.getString("label"),
                Frequency.parse(rs.getString("frequency")),
                new Money(rs.getBigDecimal("last_amount")),
                rs.getObject("last_date", LocalDate.class),
                rs.getObject("next_expected", LocalDate.class),
                rs.getBoolean("active"),
                List.of(memberIds));
    }

    /**
     * Rebuilds the three facts the classification holds, from the three columns that store them.
     *
     * <p>Stored as names rather than ordinals, so reordering either enum can never silently change
     * what existing rows mean - the one database mistake that produces no error and no clue.
     */
    private static Classification readClassification(ResultSet rs) throws SQLException {
        String category = rs.getString("category");
        if (category != null) {
            return Classification.spend(SpendCategory.valueOf(category));
        }
        TransactionKind kind = TransactionKind.valueOf(rs.getString("kind"));
        return rs.getBoolean("towards_savings") ? Classification.savings() : Classification.notSpending(kind);
    }

    /** getDouble returns 0.0 for SQL NULL, which would put every unlocated transaction off West Africa. */
    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
