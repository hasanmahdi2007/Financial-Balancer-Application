package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * Path B: the user typed what they have, with no bank connected.
 *
 * <p>The figure is taken whole. There is no share question here and no way to express one - the
 * user already filtered their money by deciding what to type, and asking them for a percentage of
 * their own chosen figure would be asking the same question twice. Wanting more in scope means
 * raising the total, and the app neither needs nor asks where the extra came from.
 *
 * <p>Nothing is set aside on this path, because nothing was ever visible to set aside.
 */
public record ManualConsideredFunds(Money statedTotal, Money monthlyIncome)
        implements ConsideredFundsSource {

    public ManualConsideredFunds {
        Objects.requireNonNull(statedTotal, "statedTotal");
        Objects.requireNonNull(monthlyIncome, "monthlyIncome");
        if (statedTotal.isNegative()) {
            throw new IllegalArgumentException("statedTotal must not be negative but was " + statedTotal);
        }
        if (monthlyIncome.isNegative()) {
            throw new IllegalArgumentException("monthlyIncome must not be negative but was " + monthlyIncome);
        }
    }

    @Override
    public ConsideredFunds resolve() {
        return new ConsideredFunds(
                statedTotal, monthlyIncome, SetAside.NOTHING.total(), ConsiderationMode.WHOLE);
    }

    /**
     * Changes what is in scope, in either direction, and nothing else.
     *
     * <p>Deliberately not named for raising. Putting more money in scope is a larger total and
     * needs no explanation of where it came from - but taking money back out is the same action and
     * must be just as easy, because a user who cannot withdraw money from a plan will stop putting
     * it in. The bank path does this by excluding an account, flagging a deposit or lowering the
     * share; this is the manual path's one equivalent.
     */
    public ManualConsideredFunds revisedTo(Money newTotal) {
        return new ManualConsideredFunds(newTotal, monthlyIncome);
    }
}
