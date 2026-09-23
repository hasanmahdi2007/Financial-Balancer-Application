package com.hasan.budget.planning.application;

import com.hasan.budget.profile.domain.ManualConsideredFunds;
import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * The money a user typed: what arrives each month, what they already have that the plan may use, and
 * what they already put away each month.
 *
 * <p>Taken whole. There is no share question on this path because the user already chose what to
 * type, and lowering the balance later is how money comes back out of the plan.
 *
 * @param alreadySaving what the user says they already move into savings each month, or null when
 *     they have not said. Null is a real answer and not a zero: it is what lets a connected bank
 *     supply the figure instead, where "0" would be the user telling us they save nothing and would
 *     rightly outrank the bank. It is shown beside the plan and never subtracted from it, because
 *     putting money away is not spending it.
 */
public record StatedMoney(Money monthlyIncome, Money balance, Money alreadySaving) {

    public StatedMoney {
        Objects.requireNonNull(monthlyIncome, "monthlyIncome");
        Objects.requireNonNull(balance, "balance");
        if (monthlyIncome.isNegative()) {
            throw new IllegalArgumentException("monthly income cannot be below zero");
        }
        if (balance.isNegative()) {
            throw new IllegalArgumentException("the money you already have cannot be below zero");
        }
        if (alreadySaving != null && alreadySaving.isNegative()) {
            throw new IllegalArgumentException("what you already put away each month cannot be below zero");
        }
    }

    /** Someone who has not been asked what they already put away. */
    public StatedMoney(Money monthlyIncome, Money balance) {
        this(monthlyIncome, balance, null);
    }

    public ManualConsideredFunds asFunds() {
        return new ManualConsideredFunds(balance, monthlyIncome);
    }
}
