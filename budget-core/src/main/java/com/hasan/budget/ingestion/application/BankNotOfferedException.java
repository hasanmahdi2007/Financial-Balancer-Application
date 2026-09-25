package com.hasan.budget.ingestion.application;

/**
 * Connecting a bank is not available to this user, with the reason and what to do instead.
 *
 * <p>Not an error in the request or in the bank: the provider simply does not reach banks where
 * this person lives, or we do not yet know where that is.
 */
public class BankNotOfferedException extends RuntimeException {

    public BankNotOfferedException(String forAPerson) {
        super(forAPerson);
    }
}
