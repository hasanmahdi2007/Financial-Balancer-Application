package com.hasan.budget.planning.application;

import com.hasan.budget.profile.domain.ManualConsideredFunds;
import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * The money a user typed, with no bank connected: what arrives each month, and what they already have
 * that the plan may use.
 *
 * <p>Taken whole. There is no share question on this path because the user already chose what to
 * type, and lowering the balance later is how money comes back out of the plan.
 */
public record StatedMoney(Money monthlyIncome, Money balance) {

    public StatedMoney {
        Objects.requireNonNull(monthlyIncome, "monthlyIncome");
        Objects.requireNonNull(balance, "balance");
        if (monthlyIncome.isNegative()) {
            throw new IllegalArgumentException("monthly income cannot be below zero");
        }
        if (balance.isNegative()) {
            throw new IllegalArgumentException("the money you already have cannot be below zero");
        }
    }

    public ManualConsideredFunds asFunds() {
        return new ManualConsideredFunds(balance, monthlyIncome);
    }
}
