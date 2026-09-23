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
 * @param alreadySaving what the user already moves into savings each month. It is shown beside the
 *     plan and never subtracted from it, because putting money away is not spending it - so a plan
 *     that charged the user for it would understate what they have left by exactly the amount they
 *     are already doing right. It is typed rather than measured: telling a savings transfer apart
 *     from a credit-card bill payment needs the kind of account the money went to, and the bank
 *     ledger does not keep that. Zero until they say otherwise, which is the only figure that
 *     cannot be wrong.
 */
public record StatedMoney(Money monthlyIncome, Money balance, Money alreadySaving) {

    public StatedMoney {
        Objects.requireNonNull(monthlyIncome, "monthlyIncome");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(alreadySaving, "alreadySaving");
        if (monthlyIncome.isNegative()) {
            throw new IllegalArgumentException("monthly income cannot be below zero");
        }
        if (balance.isNegative()) {
            throw new IllegalArgumentException("the money you already have cannot be below zero");
        }
        if (alreadySaving.isNegative()) {
            throw new IllegalArgumentException("what you already put away each month cannot be below zero");
        }
    }

    /** Someone who has not been asked what they already put away is putting away nothing we know of. */
    public StatedMoney(Money monthlyIncome, Money balance) {
        this(monthlyIncome, balance, Money.ZERO);
    }

    public ManualConsideredFunds asFunds() {
        return new ManualConsideredFunds(balance, monthlyIncome);
    }
}
