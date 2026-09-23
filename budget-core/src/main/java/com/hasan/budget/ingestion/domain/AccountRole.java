package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.TransactionKind;

/**
 * What an account does with money, which decides what a transaction on it can possibly mean.
 *
 * <p>The category alone is not enough, and the recorded sandbox proves it rather than a hypothetical
 * doing so. The credit card's own bill payment arrives tagged {@code INCOME_SALARY}, and the loan
 * accounts carry hundreds of inflows named "Loan payment". Classified by category alone, paying a
 * card bill would be booked as thousands of dollars of income and a mortgage would look like a
 * salary. The fix is not a list of special cases: it is that money arriving on a borrowing account
 * can only ever be a debt being settled, whatever the provider called it.
 *
 * <p>Three constants, each carrying its own rule, so a new provider adds a mapping rather than a
 * branch.
 */
public enum AccountRole {

    /**
     * An account the user spends out of - checking, savings, cash. The category is taken at face
     * value here, because this is the one place where money genuinely enters and leaves their life.
     */
    CASH {
        @Override
        public Classification settle(Classification byCategory, boolean moneyIn) {
            return byCategory;
        }
    },

    /**
     * A credit card. Purchases on it are spending at the moment they are made, which is why paying
     * the bill later must not be counted again. Anything arriving is therefore either that bill
     * being paid or a merchant refunding a purchase - never income.
     */
    CARD {
        @Override
        public Classification settle(Classification byCategory, boolean moneyIn) {
            if (!moneyIn || byCategory.kind() == TransactionKind.SPEND) {
                return byCategory;
            }
            return Classification.notSpending(TransactionKind.TRANSFER_INTERNAL);
        }
    },

    /**
     * A loan, mortgage or line of credit. Nothing here is cash moving in the user's life: the
     * interest is charged against the balance and the payments are the mirror of money that left a
     * cash account, where it was already counted. Treating either as real would double the debt.
     */
    LOAN {
        @Override
        public Classification settle(Classification byCategory, boolean moneyIn) {
            return Classification.notSpending(TransactionKind.TRANSFER_INTERNAL);
        }
    };

    /**
     * Applies this account's rule to what the category suggested.
     *
     * @param moneyIn true when the amount is negative in the provider's convention, meaning money
     *     arrived - the convention is inverted from intuition and is kept unflipped on purpose
     */
    public abstract Classification settle(Classification byCategory, boolean moneyIn);
}
