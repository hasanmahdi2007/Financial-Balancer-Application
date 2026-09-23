package com.hasan.budget.planning.application;

/**
 * Nothing of this user's matches. Deliberately the same answer whether the thing does not exist or
 * belongs to somebody else, so that probing ids cannot reveal another user's data even by its shape.
 */
public final class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
