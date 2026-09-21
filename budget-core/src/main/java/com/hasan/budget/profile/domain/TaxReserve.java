package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * Money held back each month by a user whose income arrives untaxed.
 *
 * <p>Always {@link Rigidity#LOCKED}, and not configurable here. The engine's job is to find money
 * to move, and a tax reserve is the one line where finding money means a bill the user cannot pay.
 * Offering it as a cut would be arithmetically valid advice with a legal consequence, so the option
 * does not exist rather than being defaulted off.
 */
public record TaxReserve(Money monthlyAmount) {

    public TaxReserve {
        Objects.requireNonNull(monthlyAmount, "monthlyAmount");
        if (monthlyAmount.isNegative()) {
            throw new IllegalArgumentException("monthlyAmount must not be negative but was " + monthlyAmount);
        }
    }

    public SpendCategory category() {
        return SpendCategory.TAX_RESERVE;
    }

    public Rigidity rigidity() {
        return Rigidity.LOCKED;
    }
}
