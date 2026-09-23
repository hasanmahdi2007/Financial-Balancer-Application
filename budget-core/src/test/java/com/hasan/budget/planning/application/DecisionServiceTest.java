package com.hasan.budget.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.planning.domain.decision.Adjustment;
import com.hasan.budget.planning.domain.decision.BudgetLine;
import com.hasan.budget.planning.domain.decision.PriceBasis;
import com.hasan.budget.planning.domain.decision.RebalanceOutcome;
import com.hasan.budget.planning.domain.decision.RebalanceResult;
import com.hasan.budget.planning.domain.decision.SpendBand;
import com.hasan.budget.planning.domain.surplus.ItemScope;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The translation behind the second product surface.
 *
 * <p>The arithmetic is the decision engine's and is tested there. What is tested here is the part
 * that could quietly mislead: which figure becomes the month's allowance, what happens when there is
 * no figure at all, and how the month's spending becomes lines that can move without money appearing
 * or disappearing between them.
 */
class DecisionServiceTest {

    /** Mid-month, so there are days left to spread a purchase over. */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 14);

    private final PlanningFixture fixture = new PlanningFixture(TODAY);
    private final DecisionService decisions = new DecisionService(
            fixture.plans(), java.time.Clock.fixed(
                    TODAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneOffset.UTC));

    @BeforeEach
    void onboard() {
        fixture.onboard("saver", Money.of(1000));
    }

    @Nested
    @DisplayName("can I afford this today")
    class Afford {

        /**
         * Fun money is limited by what the user set aside for enjoying life, not by what a city says
         * eating out costs. Using the city figure would quietly let them spend past their own answer.
         */
        @Test
        void funMoneyIsMeasuredAgainstWhatTheUserProtectedForIt() {
            DecisionService.Affordability answer = decisions.afford("saver", new DecisionService.AffordQuestion(
                    SpendCategory.DINING_OUT, SpendBand.LOW, null, null, Money.of(60)));

            assertThat(answer.assessment().allowance())
                    .as("their own eating-out share of enjoying life, not the city's $120 figure")
                    .isEqualTo(Money.of(150));
            assertThat(answer.assessment().remainingBudget()).isEqualTo(Money.of(90));
            assertThat(answer.allowanceBasis()).contains("enjoying life");
        }

        /** Everything else is measured against the local figure, with its provenance said plainly. */
        @Test
        void everythingElseIsMeasuredAgainstTheLocalFigureAndSaysWhereItCameFrom() {
            DecisionService.Affordability answer = decisions.afford("saver", new DecisionService.AffordQuestion(
                    SpendCategory.GROCERIES, null, "A big shop", Money.of(90), Money.of(100)));

            assertThat(answer.assessment().allowance()).isEqualTo(Money.of(250));
            assertThat(answer.allowanceBasis()).contains("Researched by us");
            assertThat(answer.assessment().purchase().basis()).isEqualTo(PriceBasis.USER_STATED);
        }

        /**
         * With no transactions to average, every band price is an estimate and says so. That is the
         * cold-start case, and it degrades honestly rather than presenting a guess as a measurement.
         */
        @Test
        void withNoCardPaymentsYetEveryPriceSaysItIsAnEstimate() {
            DecisionService.Affordability answer = decisions.afford("saver", new DecisionService.AffordQuestion(
                    SpendCategory.DINING_OUT, SpendBand.FANCY, null, null, Money.ZERO));

            assertThat(answer.assessment().purchase().basis()).isEqualTo(PriceBasis.ESTIMATED);
            // A warning never travels alone: the next rung down comes back with its own verdict.
            assertThat(answer.assessment().cheaper()).isPresent();
            assertThat(answer.assessment().cheaper().orElseThrow().estimate().band()).isEqualTo(SpendBand.HIGH);
        }

        @Test
        void aCategoryWeHaveNoFigureForAsksForOneRatherThanGuessing() {
            assertThatThrownBy(() -> decisions.afford("saver", new DecisionService.AffordQuestion(
                            SpendCategory.DEBT_PAYMENT, null, "Car loan", Money.of(100), Money.ZERO)))
                    .isInstanceOf(NeedsMoreInformationException.class)
                    .hasMessageContaining("Tell us what you usually spend");
        }

        @Test
        void kindsOfMealOnlyApplyToEatingOut() {
            assertThatThrownBy(() -> decisions.afford("saver", new DecisionService.AffordQuestion(
                            SpendCategory.CLOTHING, SpendBand.MEDIUM, null, null, Money.ZERO)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only apply to eating out");
        }

        /** Assuming nothing had been spent yet would make every answer look more affordable than it is. */
        @Test
        void whatHasAlreadyGoneOnItThisMonthIsAskedForRatherThanAssumed() {
            assertThatThrownBy(() -> new DecisionService.AffordQuestion(
                            SpendCategory.DINING_OUT, SpendBand.LOW, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already gone on this");
        }
    }

    @Nested
    @DisplayName("give me more for this")
    class Rebalance {

        @Test
        void anIncreaseComesOutOfWhatTheUserCaresLeastAbout() {
            RebalanceResult result = decisions.rebalance("saver", new DecisionService.RebalanceQuestion(
                    "dining-out", Money.of(20), null));

            assertThat(result.outcome()).isEqualTo(RebalanceOutcome.ABSORBED);
            assertThat(result.granted()).isEqualTo(Money.of(20));
            assertThat(result.net())
                    .as("money moved between lines; none was created")
                    .isEqualTo(Money.ZERO);
            assertThat(result.adjustments()).extracting(Adjustment::lineItemId).contains("dining-out");
        }

        /** The user may think in percentages; the plan only ever stores the amount that came out. */
        @Test
        void aPercentageIsConvertedAgainstTheBalanceInScope() {
            RebalanceResult asPercent = decisions.rebalance("saver", new DecisionService.RebalanceQuestion(
                    "dining-out", null, 2));
            RebalanceResult asAmount = decisions.rebalance("saver", new DecisionService.RebalanceQuestion(
                    "dining-out", Money.of(20), null));

            assertThat(asPercent.granted()).isEqualTo(asAmount.granted());
        }

        @Test
        void aLineThatIsNotInTheirPlanIsNotFound() {
            assertThatThrownBy(() -> decisions.rebalance("saver", new DecisionService.RebalanceQuestion(
                            "yacht", Money.of(10), null)))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("no line called");
        }

        @Test
        void anIncreaseHasToBeMoreThanNothing() {
            assertThatThrownBy(() -> decisions.rebalance("saver", new DecisionService.RebalanceQuestion(
                            "dining-out", null, 0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("more than zero");
        }

        /**
         * A commitment named inside a category is its own line, and its category's line is smaller by
         * exactly that much - so the plan still totals what the user spends and no dollar can be moved
         * twice.
         */
        @Test
        void aNamedCommitmentIsItsOwnLineAndComesOutOfItsCategory() {
            fixture.plans().saveSpending("saver", java.util.Map.of(
                    SpendCategory.RENT, Money.of(600),
                    SpendCategory.SUBSCRIPTIONS, Money.of(60),
                    SpendCategory.DINING_OUT, Money.of(150)));
            fixture.plans().saveLineItem("saver", new UserLineItem(
                    "gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45), Rigidity.LOCKED,
                    ItemScope.ALREADY_COUNTED));

            var lines = DecisionService.budgetLines(fixture.plans().assemble("saver"));

            assertThat(lines).extracting(BudgetLine::id).contains("gym", "subscriptions");
            assertThat(amountOf(lines, "gym")).isEqualTo(Money.of(45));
            assertThat(amountOf(lines, "subscriptions"))
                    .as("the gym's money is named once, not counted in its category as well")
                    .isEqualTo(Money.of(15));
            // A locked line may not give, whatever else the rebalance needs.
            assertThat(lines.stream().filter(line -> line.id().equals("gym")).findFirst().orElseThrow().headroom())
                    .isEqualTo(Money.ZERO);
        }

        private static Money amountOf(java.util.List<BudgetLine> lines, String id) {
            return lines.stream()
                    .filter(line -> line.id().equals(id))
                    .findFirst()
                    .orElseThrow()
                    .amount();
        }
    }
}
