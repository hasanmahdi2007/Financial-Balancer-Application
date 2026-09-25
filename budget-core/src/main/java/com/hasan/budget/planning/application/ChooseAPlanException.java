package com.hasan.budget.planning.application;

/**
 * The user asked to change where a plan is for, after that plan has already been made.
 *
 * <p>That is a move, not a correction, and a move never re-prices the old plan with the new place's
 * figures: it resumes a plan the user already had in the new country, or starts a new one. The
 * message says so in words, and the client takes the user to that choice rather than showing an error.
 */
public final class ChooseAPlanException extends RuntimeException {

    public ChooseAPlanException(String message) {
        super(message);
    }
}
