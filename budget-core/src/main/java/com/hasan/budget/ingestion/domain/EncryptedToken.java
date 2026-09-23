package com.hasan.budget.ingestion.domain;

import java.util.Objects;

/**
 * A bank access token as it is allowed to exist anywhere outside the moment it is used.
 *
 * <p>The plaintext token is enough to read someone's entire financial life, so it lives encrypted at
 * rest and is decrypted only for the length of one call to the provider. This type exists so that
 * the encrypted form is what gets passed around and stored, and so that the plaintext has no type of
 * its own that could casually be logged, returned from a controller, or put in a record whose
 * generated {@code toString} prints every field.
 *
 * <p>{@code toString} is overridden deliberately: the commonest way a secret reaches a log is not a
 * developer printing it, it is an object containing it being printed for some other reason.
 */
public record EncryptedToken(String value) {

    public EncryptedToken {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("an encrypted token must not be blank");
        }
    }

    @Override
    public String toString() {
        return "EncryptedToken[not shown]";
    }
}
