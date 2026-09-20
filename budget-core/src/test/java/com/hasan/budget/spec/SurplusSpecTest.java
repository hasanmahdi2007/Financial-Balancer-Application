package com.hasan.budget.spec;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Executable specification for the surplus formula (Stage 1).
 *
 * <p>Written before the code so the requirement is fixed in advance rather than reverse-engineered
 * from whatever gets built. Each case carries the worked numbers, so implementing it is filling in
 * a body and deleting the {@code @Disabled}.
 *
 * <p>The formula under specification:
 *
 * <pre>
 * surplus = income
 *         − fixedCommitments                 (taken as-is, never capped)
 *         − Σ min(actual, localBaseline)     (flexible essentials only)
 *         − discretionaryFloor               (quality-of-life minimum)
 * </pre>
 */
@Disabled("Stage 1: planning.domain.surplus does not exist yet")
class SurplusSpecTest {

    @Nested
    @DisplayName("fixed commitments are never capped")
    class FixedCommitments {

        /**
         * The bug that nearly shipped. A cost-of-living index says what things COST, not what you
         * SHOULD spend; capping rent at the baseline pretends the user can pay less than their
         * lease this month, which overstates the surplus by the difference.
         *
         * <p>Rent actual $2,400, San Francisco baseline $2,100, income $6,000.
         * Expected: $2,400 is subtracted, not $2,100. Surplus reflects the real $2,400 outflow.
         */
        @Test
        void rentAboveTheLocalBaselineIsStillSubtractedInFull() {
            fail("not implemented");
        }

        /**
         * The inverse, and just as important: a cheap lease must not be inflated up to the
         * baseline. Rent actual $900, baseline $2,100 ⇒ $900 subtracted.
         */
        @Test
        void rentBelowTheLocalBaselineIsSubtractedAtWhatIsActuallyPaid() {
            fail("not implemented");
        }

        /** DEBT_PAYMENT, HEALTHCARE and SUBSCRIPTIONS follow the same rule as RENT. */
        @Test
        void everyTakeAsIsCategoryIsSubtractedAtItsActualAmount() {
            fail("not implemented");
        }
    }

    @Nested
    @DisplayName("flexible essentials are capped at the local baseline")
    class FlexibleEssentials {

        /**
         * Groceries actual $700, baseline $450 ⇒ $450 subtracted. The $250 above baseline is
         * treated as controllable, which is what lets the plan say "this is where your goal went".
         */
        @Test
        void spendingAboveBaselineIsCappedSoTheExcessCountsAsControllable() {
            fail("not implemented");
        }

        /** Groceries actual $300, baseline $450 ⇒ $300 subtracted. Never inflate to the baseline. */
        @Test
        void spendingBelowBaselineUsesTheActualAmount() {
            fail("not implemented");
        }

        /** Only CAP_AT_BASELINE categories are capped; nothing else consults the baseline at all. */
        @Test
        void noOtherCategoryConsultsTheBaseline() {
            fail("not implemented");
        }
    }

    @Nested
    @DisplayName("transfers are not spending")
    class Transfers {

        /**
         * A $500 move into savings must not be subtracted. Counting it as spend understates the
         * surplus by $500 and then the goal it funds is counted separately: the same money twice.
         * It is reported as "you already save $500/mo" and flows into the surplus.
         */
        @Test
        void aTransferIntoSavingsIsReportedButNeverSubtracted() {
            fail("not implemented");
        }

        /**
         * The sharper half of the same bug. $300 of groceries bought on a card, then a $300 card
         * payment: the groceries are counted once, at purchase. Counting the payment too would
         * subtract $600 for $300 of food.
         */
        @Test
        void payingOffACreditCardIsNotSpending() {
            fail("not implemented");
        }

        /**
         * Distinct from the above: a car loan instalment is a real outflow to a lender, not the
         * settlement of spending already counted, so DEBT_PAYMENT is subtracted in full.
         */
        @Test
        void aLoanInstalmentIsRealSpendingUnlikeACardPayment() {
            fail("not implemented");
        }
    }

    @Nested
    @DisplayName("arithmetic properties that must always hold")
    class Invariants {

        /**
         * Conservation. income == fixed + capped + floor + discretionary + surplus, exactly, with
         * no cent created or lost. This is the property most likely to catch a refactor.
         */
        @Test
        void everyCentOfIncomeIsAccountedForInTheBreakdown() {
            fail("not implemented");
        }

        /**
         * Essentials can exceed income, and the answer is a negative surplus, not zero. Clamping
         * to zero would hide the overspend and make the tradeoff engine understate the cuts needed.
         */
        @Test
        void surplusGoesNegativeWhenEssentialsExceedIncome() {
            fail("not implemented");
        }

        /** The discretionary floor is subtracted whole; it is a minimum, not a target. */
        @Test
        void theDiscretionaryFloorIsSubtractedInFull() {
            fail("not implemented");
        }

        /**
         * No clock. Two runs with identical inputs and the same asOf must produce identical output,
         * today and next year. ArchitectureTest enforces the mechanism; this asserts the result.
         */
        @Test
        void theSameInputsAlwaysProduceTheSameBreakdown() {
            fail("not implemented");
        }
    }

    /**
     * The headline loop, end to end and with zero I/O: income and observed spending plus baselines
     * produce a surplus, the surplus plus goals produce a plan, and a goal that does not fit
     * produces a concrete tradeoff. If this passes, the product works.
     */
    @Test
    void incomeAndSpendingProduceAPlanWithConcreteTradeoffs() {
        fail("not implemented");
    }
}
