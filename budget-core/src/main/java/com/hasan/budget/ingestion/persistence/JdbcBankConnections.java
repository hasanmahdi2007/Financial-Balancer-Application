package com.hasan.budget.ingestion.persistence;

import com.hasan.budget.ingestion.domain.BankConnection;
import com.hasan.budget.ingestion.domain.ConnectionStatus;
import com.hasan.budget.ingestion.domain.EncryptedToken;
import com.hasan.budget.ingestion.port.BankConnections;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Connected banks, in Postgres. */
@Repository
public class JdbcBankConnections implements BankConnections {

    private final JdbcClient jdbc;

    public JdbcBankConnections(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts, or refreshes the credential of a connection this user already has.
     *
     * <p>The {@code WHERE} on the conflict clause is the safety property, not an optimisation.
     * Without it, re-linking a bank that is already connected to a different account would hand that
     * account's transaction history to whoever linked it second. With it, the update matches nothing,
     * no row is returned, and the attempt fails loudly instead.
     */
    @Override
    @Transactional
    public BankConnection connect(String userId, String providerItemId, EncryptedToken accessToken) {
        return jdbc.sql(
                        """
                        INSERT INTO bank_connection (user_id, provider_item_id, access_token_ciphertext)
                        VALUES (:userId, :providerItemId, :ciphertext)
                        ON CONFLICT (provider_item_id) DO UPDATE
                            SET access_token_ciphertext = EXCLUDED.access_token_ciphertext
                            WHERE bank_connection.user_id = EXCLUDED.user_id
                        RETURNING id, user_id, provider_item_id, access_token_ciphertext
                        """)
                .param("userId", userId)
                .param("providerItemId", providerItemId)
                .param("ciphertext", accessToken.value())
                .query(JdbcBankConnections::readConnection)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "bank connection " + providerItemId + " is already held by another account"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BankConnection> find(long connectionId) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", connectionId)
                .query(JdbcBankConnections::readConnection)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BankConnection> findByProviderItemId(String providerItemId) {
        return jdbc.sql(SELECT + " WHERE provider_item_id = :providerItemId")
                .param("providerItemId", providerItemId)
                .query(JdbcBankConnections::readConnection)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BankConnection> forUser(String userId) {
        return jdbc.sql(SELECT + " WHERE user_id = :userId ORDER BY id")
                .param("userId", userId)
                .query(JdbcBankConnections::readConnection)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectionStatus> statusForUser(String userId) {
        return jdbc.sql("SELECT id, connected_at, last_synced_at FROM bank_connection WHERE user_id = :userId ORDER BY id")
                .param("userId", userId)
                .query((row, n) -> new ConnectionStatus(
                        row.getLong("id"),
                        row.getObject("connected_at", OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(row.getObject("last_synced_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)
                                .orElse(null)))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> allIds() {
        return jdbc.sql("SELECT id FROM bank_connection ORDER BY id").query(Long.class).list();
    }

    /** Transactions, streams and balances go with it, by the cascades on every table that names it. */
    @Override
    @Transactional
    public boolean disconnect(long connectionId, String userId) {
        return jdbc.sql("DELETE FROM bank_connection WHERE id = :id AND user_id = :userId")
                        .param("id", connectionId)
                        .param("userId", userId)
                        .update()
                > 0;
    }

    private static final String SELECT =
            "SELECT id, user_id, provider_item_id, access_token_ciphertext FROM bank_connection";

    private static BankConnection readConnection(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new BankConnection(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("provider_item_id"),
                new EncryptedToken(rs.getString("access_token_ciphertext")));
    }
}
