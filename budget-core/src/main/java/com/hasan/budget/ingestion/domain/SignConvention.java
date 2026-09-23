package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.Money;

/**
 * The one place the provider's sign convention is turned into what a person expects to read.
 *
 * <p>Plaid: <em>"Positive values when money moves out of the account; negative values when money
 * moves in."</em> A $700 salary deposit therefore arrives as {@code -700}. Stored amounts keep that
 * convention deliberately - flipping it at the edge would hide the trap rather than contain it, and
 * every arithmetic rule in this module is written against it.
 *
 * <p>Getting this backwards inverts every number in the product and raises no error anywhere, which
 * is why the flip lives in a named function with a test rather than as a minus sign somewhere in a
 * view.
 */
public final class SignConvention {

    private SignConvention() {}

    /** Money in reads positive, money out reads negative - the opposite of how it is stored. */
    public static Money shownToUser(Money storedAmount) {
        return Money.ZERO.minus(storedAmount);
    }

    /** True when this amount is money leaving the account, in the stored convention. */
    public static boolean isMoneyOut(Money storedAmount) {
        return storedAmount.isPositive();
    }

    /** True when this amount is money arriving, in the stored convention. */
    public static boolean isMoneyIn(Money storedAmount) {
        return storedAmount.isNegative();
    }
}
