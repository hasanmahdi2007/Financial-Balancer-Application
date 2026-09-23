package com.hasan.budget.planning.application;

/**
 * A plan cannot be made yet because the user has not told us something it depends on. The message
 * says what, in words the user can act on.
 */
public final class NeedsMoreInformationException extends RuntimeException {

    public NeedsMoreInformationException(String message) {
        super(message);
    }
}
