package com.hasan.budget.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.shared.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cases around the edges of the two entry paths: the ones a user reaches by accident and a
 * caller reaches by misreading the model.
 */
class ConsideredFundsSourceTest {

    private static final BankAccountBalance CURRENT =
            BankAccountBalance.visible("chk", "Current account", Money.of(10_000));

    /**
     * Taking a connected bank balance whole is the manual path's behaviour, and allowing it here
     * would quietly skip the one question that entry path exists to ask.
     */
    @Test
    void aConnectedBankCannotSkipTheShareQuestion() {
        assertThatThrownBy(() -> new BankConsideredFunds(
                        List.of(CURRENT), List.of(), ConsiderationMode.WHOLE, Money.of(10_000), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("share is in scope");
    }

    /** A share and a named figure are two different answers to one question. */
    @Test
    void aShareAndANamedFigureCannotBothBeGiven() {
        assertThatThrownBy(() -> new BankConsideredFunds(
                        List.of(CURRENT),
                        List.of(),
                        ConsiderationMode.PERCENTAGE,
                        Money.of(5_000),
                        Rate.ofPercent("60")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BankConsideredFunds(
                        List.of(CURRENT),
                        List.of(),
                        ConsiderationMode.ABSOLUTE,
                        Money.of(5_000),
                        Rate.ofPercent("60")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Flagging more than the account holds is a data problem, not a reason to plan with a negative
     * balance. The pool bottoms out at nothing and the plan simply has nothing to work with.
     */
    @Test
    void ringFencingMoreThanTheAccountHoldsLeavesNothingRatherThanLessThanNothing() {
        BankConsideredFunds source = BankConsideredFunds.share(
                List.of(CURRENT),
                List.of(Deposit.ringFenced("inheritance", "chk", Money.of(25_000))),
                Rate.ofPercent("100"));

        assertThat(source.consideredBalance()).isEqualTo(Money.ZERO);
    }

    /**
     * A flagged deposit sitting in an excluded account is already invisible through the account, so
     * counting it again would overstate what was set aside and break the identity that every
     * visible dollar is either planned with or explained.
     */
    @Test
    void aFlaggedDepositInsideAnExcludedAccountIsCountedOnce() {
        BankConsideredFunds source = BankConsideredFunds.share(
                List.of(
                        CURRENT,
                        BankAccountBalance.excluded("sav", "Savings", Money.of(9_000))),
                List.of(Deposit.ringFenced("from-family", "sav", Money.of(7_000))),
                Rate.ofPercent("100"));

        assertThat(source.setAsideBreakdown().flaggedDeposits()).isEqualTo(Money.ZERO);
        assertThat(source.resolve().setAside()).isEqualTo(Money.of(9_000));
        assertThat(source.consideredBalance().plus(source.resolve().setAside()))
                .isEqualTo(source.visibleBalance());
    }

    /** Money arriving is stated in the natural direction, so the bank's sign convention cannot leak in. */
    @Test
    void aDepositStatedAsMoneyLeavingIsRejected() {
        assertThatThrownBy(() -> Deposit.of("payroll", "chk", Money.of(-4_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("money arriving");
    }

    /**
     * One of exactly two ways a balance may influence a plan. The other is raising a goal's saved
     * amount; neither hands the balance to the allocator, which is the seam an architecture rule
     * keeps shut.
     */
    @Test
    void theRunwayIsHowLongTheBalanceCoversAShortfall() {
        ConsideredFunds funds = new ManualConsideredFunds(Money.of(12_000), Money.of(4_000)).resolve();

        assertThat(Runway.of(funds, Money.of(2_500)))
                .describedAs("four whole months of cover, not four and four fifths")
                .isEqualTo(new Runway(4, false));
    }

    /** With no shortfall there is nothing to run down, and a month count would be meaningless. */
    @Test
    void thereIsNoRunwayToCountWhenNothingIsBeingRunDown() {
        ConsideredFunds funds = new ManualConsideredFunds(Money.of(12_000), Money.of(4_000)).resolve();

        assertThat(Runway.of(funds, Money.ZERO).indefinite()).isTrue();
        assertThat(Runway.of(funds, Money.of(-300)).indefinite()).isTrue();
    }
}
