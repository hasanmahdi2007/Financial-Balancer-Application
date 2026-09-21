package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * Everything the app has agreed not to plan with, split by the route it took to get there.
 *
 * <p>Three routes reach one total, and the total is shown rather than implied. A user who sees a
 * balance of $120,000 in their bank and $74,400 in this app, with no account of the difference,
 * stops trusting every other number on the page. The split is kept because "why is it set aside?"
 * is the immediate next question, and only the route can answer it.
 */
public record SetAside(Money excludedAccounts, Money flaggedDeposits, Money withheldShare) {

    public static final SetAside NOTHING = new SetAside(Money.ZERO, Money.ZERO, Money.ZERO);

    public SetAside {
        Objects.requireNonNull(excludedAccounts, "excludedAccounts");
        Objects.requireNonNull(flaggedDeposits, "flaggedDeposits");
        Objects.requireNonNull(withheldShare, "withheldShare");
    }

    public Money total() {
        return excludedAccounts.plus(flaggedDeposits).plus(withheldShare);
    }
}
