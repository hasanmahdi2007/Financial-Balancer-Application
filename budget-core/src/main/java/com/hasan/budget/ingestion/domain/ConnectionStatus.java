package com.hasan.budget.ingestion.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * What a user may be told about one of their connected banks: when it was linked and when it last
 * finished importing.
 *
 * <p>Deliberately not {@link BankConnection}, which carries the encrypted credential. Anything built
 * to be shown to a person is kept structurally unable to hold a token, so no view can leak one by
 * serialising the wrong field.
 *
 * @param lastSyncedAt null until the first import completes, which is how "still importing" is told
 *     apart from "imported and found nothing"
 */
public record ConnectionStatus(long id, Instant connectedAt, Instant lastSyncedAt) {

    public ConnectionStatus {
        Objects.requireNonNull(connectedAt, "connectedAt");
    }

    public boolean hasImported() {
        return lastSyncedAt != null;
    }
}
