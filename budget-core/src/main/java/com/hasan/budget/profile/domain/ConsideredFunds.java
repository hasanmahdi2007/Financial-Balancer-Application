package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * The money the user has agreed the app may plan with.
 *
 * <p>Deliberately two separate quantities. {@code monthlyIncome} is a rate; {@code consideredBalance}
 * is a stock, and the engine had no concept of one before. They are not interchangeable.
 *
 * <p><strong>The balance never reaches the allocator.</strong> It influences a plan only by raising a
 * goal's saved amount, which shrinks what that goal needs each month, and by producing a runway
 * figure for display. The allocator takes a single monthly surplus and nothing else; that narrow
 * input is the most valuable structural property in the project and an ArchUnit rule guards it.
 *
 * @param setAside everything excluded - whole accounts, flagged one-off deposits, and the share the
 *     user held back. Shown as one visible total, because money the user cannot see is money they
 *     stop trusting the app about.
 */
public record ConsideredFunds(
        Money consideredBalance, Money monthlyIncome, Money setAside, ConsiderationMode mode) {

    public ConsideredFunds {
        Objects.requireNonNull(consideredBalance, "consideredBalance");
        Objects.requireNonNull(monthlyIncome, "monthlyIncome");
        Objects.requireNonNull(setAside, "setAside");
        Objects.requireNonNull(mode, "mode");
    }
}
