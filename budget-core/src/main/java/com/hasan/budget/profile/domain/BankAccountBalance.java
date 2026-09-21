package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * One connected account and what is in it.
 *
 * @param excluded true when the user has asked for this account to be invisible. An excluded
 *     account contributes nothing to the considered balance, nothing to income, and nothing to any
 *     category - but its balance still appears in the set-aside total, because money the user
 *     cannot see is money they stop trusting the app about.
 */
public record BankAccountBalance(String accountId, String label, Money balance, boolean excluded) {

    public BankAccountBalance {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(balance, "balance");
    }

    /** A visible account, for the common case where the user has excluded nothing. */
    public static BankAccountBalance visible(String accountId, String label, Money balance) {
        return new BankAccountBalance(accountId, label, balance, false);
    }

    public static BankAccountBalance excluded(String accountId, String label, Money balance) {
        return new BankAccountBalance(accountId, label, balance, true);
    }
}
