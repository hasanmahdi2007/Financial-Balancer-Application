package com.hasan.budget.ingestion.application;

/**
 * The bank, or our credentials for it, could not be used just now.
 *
 * <p>Carries a sentence meant for a person. The technical reason - a provider error code, a missing
 * key - stays in the cause and the log, because it tells a user nothing they can act on and could
 * tell an attacker something about how the service is configured.
 */
public class BankUnavailableException extends RuntimeException {

    public BankUnavailableException(String forAPerson, Throwable cause) {
        super(forAPerson, cause);
    }
}
