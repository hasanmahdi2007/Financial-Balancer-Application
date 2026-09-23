package com.hasan.budget.ingestion.domain;

import java.util.Objects;

/**
 * One bank the user has connected, and the credential that reads it.
 *
 * <p>The access token is held only in its encrypted form, so nothing that carries a connection
 * around is carrying a usable credential.
 *
 * @param providerItemId the provider's own identifier for this connection. Webhooks arrive naming
 *     it and nothing else, so it is the only way to know which user a notification concerns.
 * @param userId every read of bank data is scoped by this. It is the difference between a private
 *     financial record and a shared one.
 */
public record BankConnection(long id, String userId, String providerItemId, EncryptedToken accessToken) {

    public BankConnection {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(providerItemId, "providerItemId");
        Objects.requireNonNull(accessToken, "accessToken");
    }
}
