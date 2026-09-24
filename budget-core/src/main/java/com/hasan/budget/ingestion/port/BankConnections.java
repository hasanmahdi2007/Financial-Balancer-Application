package com.hasan.budget.ingestion.port;

import com.hasan.budget.ingestion.domain.BankConnection;
import com.hasan.budget.ingestion.domain.ConnectionStatus;
import com.hasan.budget.ingestion.domain.EncryptedToken;
import java.util.List;
import java.util.Optional;

/** Where connected banks and their encrypted credentials are kept. */
public interface BankConnections {

    /**
     * Records a connection, or replaces the credential on one that already exists.
     *
     * <p>Re-linking the same bank is ordinary - a password change or an expired consent sends the
     * user back through the widget and the provider returns the same connection with a fresh token.
     * Replacing the credential in place keeps the transaction history and the sync cursor, so a
     * re-link resumes rather than re-importing years of data.
     *
     * @throws IllegalStateException if the connection already belongs to a different user, which
     *     would otherwise silently hand one person's bank data to another
     */
    BankConnection connect(String userId, String providerItemId, EncryptedToken accessToken);

    Optional<BankConnection> find(long connectionId);

    /** How a webhook naming only the provider's identifier is traced back to a user. */
    Optional<BankConnection> findByProviderItemId(String providerItemId);

    List<BankConnection> forUser(String userId);

    /** What this user may be told about their connections, without any credential in it. */
    List<ConnectionStatus> statusForUser(String userId);

    /** Every connection there is, for the scheduled refresh that keeps each one current. */
    List<Long> allIds();

    /**
     * Forgets a connection and everything imported through it.
     *
     * <p>Scoped by user as well as by id, so that a request naming somebody else's connection
     * removes nothing rather than removing theirs.
     *
     * @return false when this user holds no such connection
     */
    boolean disconnect(long connectionId, String userId);
}
