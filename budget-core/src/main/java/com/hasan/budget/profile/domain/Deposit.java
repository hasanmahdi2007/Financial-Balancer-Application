package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * Money that arrived in an account over the month being planned.
 *
 * <p>Stated in the natural direction - a positive amount is money arriving - rather than in the
 * bank's convention, where a deposit is negative. The flip happens once, at the ingestion boundary
 * that owns that quirk, so nothing downstream has to remember it.
 *
 * @param ringFenced true for the $7,000-from-family case: a one-off the user has flagged as not
 *     theirs to plan with. It never reaches income, and therefore never reaches the surplus.
 */
public record Deposit(String reference, String accountId, Money amount, boolean ringFenced) {

    public Deposit {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                    "a deposit is money arriving, so it must not be negative but was " + amount);
        }
    }

    public static Deposit of(String reference, String accountId, Money amount) {
        return new Deposit(reference, accountId, amount, false);
    }

    /** A deposit the user has flagged as not theirs to plan with. */
    public static Deposit ringFenced(String reference, String accountId, Money amount) {
        return new Deposit(reference, accountId, amount, true);
    }
}
