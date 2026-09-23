package com.hasan.budget.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that stop a category meaning the wrong thing because of where it landed.
 *
 * <p>Each case here is something the recorded sandbox actually sends. None of them would raise an
 * error if they were wrong; they would simply make a plan out of numbers that are not true.
 */
class AccountRoleTest {

    private static final Classification SALARY = Classification.notSpending(TransactionKind.INCOME);
    private static final Classification GROCERIES = Classification.spend(SpendCategory.GROCERIES);
    private static final Classification A_TRANSFER = Classification.notSpending(TransactionKind.TRANSFER_INTERNAL);
    private static final boolean MONEY_IN = true;
    private static final boolean MONEY_OUT = false;

    @Test
    @DisplayName("a cash account is taken at face value in both directions")
    void cashKeepsWhateverTheCategorySaid() {
        assertThat(AccountRole.CASH.settle(GROCERIES, MONEY_OUT)).isEqualTo(GROCERIES);
        assertThat(AccountRole.CASH.settle(SALARY, MONEY_IN)).isEqualTo(SALARY);
    }

    @Test
    @DisplayName("money arriving on a credit card is never income, however it was tagged")
    void aCardCreditIsADebtBeingSettled() {
        // The sandbox tags the card's own bill payment INCOME_SALARY. Believing it invents income.
        assertThat(AccountRole.CARD.settle(SALARY, MONEY_IN)).isEqualTo(A_TRANSFER);
    }

    @Test
    @DisplayName("a refund on a card stays a refund rather than becoming a transfer")
    void aCardCreditFromAMerchantIsStillAboutSpending() {
        assertThat(AccountRole.CARD.settle(GROCERIES, MONEY_IN)).isEqualTo(GROCERIES);
    }

    @Test
    @DisplayName("a purchase on a card is spending at the moment it is made")
    void aCardPurchaseIsSpending() {
        assertThat(AccountRole.CARD.settle(GROCERIES, MONEY_OUT)).isEqualTo(GROCERIES);
    }

    @Test
    @DisplayName("nothing on a loan account is spending or income, in either direction")
    void aLoanAccountOnlyEverMirrorsMoneyThatMovedElsewhere() {
        // Interest charged against the balance, and the payments that arrive to reduce it. The cash
        // for both left a current account, where it was counted once already.
        assertThat(AccountRole.LOAN.settle(Classification.notSpending(TransactionKind.FEE), MONEY_OUT))
                .isEqualTo(A_TRANSFER);
        assertThat(AccountRole.LOAN.settle(SALARY, MONEY_IN)).isEqualTo(A_TRANSFER);
        assertThat(AccountRole.LOAN.settle(Classification.spend(SpendCategory.DEBT_PAYMENT), MONEY_OUT))
                .isEqualTo(A_TRANSFER);
    }

    @Test
    @DisplayName("the display flip is the only place the provider's sign is inverted")
    void moneyInReadsPositiveToAPerson() {
        assertThat(SignConvention.shownToUser(com.hasan.budget.shared.Money.of("-810.00")))
                .isEqualTo(com.hasan.budget.shared.Money.of("810.00"));
        assertThat(SignConvention.shownToUser(com.hasan.budget.shared.Money.of("19.57")))
                .isEqualTo(com.hasan.budget.shared.Money.of("-19.57"));
        assertThat(SignConvention.isMoneyOut(com.hasan.budget.shared.Money.of("19.57")))
                .isTrue();
        assertThat(SignConvention.isMoneyIn(com.hasan.budget.shared.Money.of("-19.57")))
                .isTrue();
        // Zero is neither, and must not be counted as both.
        assertThat(SignConvention.isMoneyIn(com.hasan.budget.shared.Money.ZERO)).isFalse();
        assertThat(SignConvention.isMoneyOut(com.hasan.budget.shared.Money.ZERO)).isFalse();
    }
}
