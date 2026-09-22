package com.hasan.budget.planning.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parts of the affordability check the executable specification does not reach: the middle
 * verdict and its exact boundary, the guarantee that a plan never overshoots the allowance on days
 * that do not divide evenly, and the input a caller can get wrong.
 */
class SpendDecisionTest {

    private static final Money ALLOWANCE = Money.of(600);
    private static final Money SPENT = Money.of(200);
    private static final LocalDate THE_TENTH = LocalDate.of(2026, 6, 10);

    /** $400 left over 21 days, rounded down to the cent. */
    private static final Money SUSTAINABLE_DAILY = Money.of("19.04");

    /**
     * The reason the verdict has three values rather than two. A meal a dollar over the daily rate is
     * affordable in any sense a person cares about, and reporting it as over budget teaches them to
     * ignore the verdict entirely — which costs far more than the dollar.
     */
    @Test
    void aPurchaseSlightlyAboveTheDailyRateIsSustainableRatherThanOverBudget() {
        SpendAssessment assessment = decide(Money.of(20));

        assertThat(assessment.sustainableDaily()).isEqualTo(SUSTAINABLE_DAILY);
        assertThat(assessment.verdict()).isEqualTo(SpendVerdict.SUSTAINABLE);
        // Still needs making up afterwards, and the plan says how: four cents a day for twenty days.
        assertThat(assessment.catchUp().orElseThrow().fitsThisMonth()).isTrue();
        assertThat(assessment.catchUp().orElseThrow().reductionPerDay()).isEqualTo(Money.of("0.04"));
    }

    /**
     * The boundary itself, asserted at the cent, because "within a tolerance band" is the sort of
     * phrase that silently becomes a different band under a refactor. A tenth above $19.04 is $20.95
     * once rounded outward, so $20.95 is still sustainable and $20.96 is not.
     */
    @Test
    void theSustainableToleranceEndsExactlyWhereItSaysItDoes() {
        assertThat(SpendDecision.SUSTAINABLE_TOLERANCE_PERCENT).isEqualTo(10);

        assertThat(decide(SUSTAINABLE_DAILY).verdict()).isEqualTo(SpendVerdict.COMFORTABLE);
        assertThat(decide(Money.of("19.05")).verdict()).isEqualTo(SpendVerdict.SUSTAINABLE);
        assertThat(decide(Money.of("20.95")).verdict()).isEqualTo(SpendVerdict.SUSTAINABLE);
        assertThat(decide(Money.of("20.96")).verdict()).isEqualTo(SpendVerdict.OVER_BUDGET);
    }

    /**
     * The promise the catch-up plan makes, on every day of a month whose divisions are not exact.
     * Following the plan must never spend more than is left, because a plan that overshoots by a cent
     * a day is not a plan to get back on track — it is the same problem again, quieter. Equality is
     * not claimed here: on days where the money does not divide evenly the plan lands a few cents
     * under, which is the direction that keeps the user on track.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 13, 17, 23, 28, 29, 30, 31})
    void followingTheCatchUpPlanNeverSpendsMoreThanIsLeft(int dayOfMonth) {
        // A 31-day month, so nothing divides evenly and the rounding has somewhere to hide.
        LocalDate asOf = YearMonth.of(2026, 7).atDay(dayOfMonth);
        Money price = Money.of(25);

        SpendAssessment assessment = SpendDecision.decide(new SpendDecisionRequest(
                SpendCategory.DINING_OUT,
                ALLOWANCE,
                SPENT,
                TicketEstimate.stated("Lunch", "a sit-down lunch", price),
                PriceLadder.none(),
                asOf));

        assertThat(assessment.remainingDays()).isEqualTo(32 - dayOfMonth);

        if (assessment.catchUp().isEmpty()) {
            // Late in the month the whole remainder is one day's to spend, so a $25 lunch needs no
            // making up at all and no plan is offered.
            assertThat(assessment.verdict()).isEqualTo(SpendVerdict.COMFORTABLE);
            return;
        }
        CatchUpPlan plan = assessment.catchUp().orElseThrow();
        assertThat(plan.totalIfFollowed(price))
                .as("day %s must not overshoot what is left", dayOfMonth)
                .isLessThanOrEqualTo(assessment.remainingBudget());
        // And it must not undershoot by more than the rounding: a cent a day at most, which is the
        // price of never overshooting.
        assertThat(plan.totalIfFollowed(price))
                .as("day %s must not leave money unexplained", dayOfMonth)
                .isGreaterThan(assessment.remainingBudget().minus(Money.of("0.01").times(plan.days() + 1)));
    }

    /**
     * A user past their allowance is told they have nothing per day, not that they have a negative
     * amount per day. The overspend itself is still reported on the assessment, so clamping the rate
     * hides nothing.
     */
    @Test
    void theDailyRateIsNeverNegativeEvenWhenTheAllowanceIsGone() {
        SpendAssessment assessment = SpendDecision.decide(new SpendDecisionRequest(
                SpendCategory.DINING_OUT,
                ALLOWANCE,
                Money.of(900),
                TicketEstimate.stated("Coffee", "a flat white", Money.of(4)),
                PriceLadder.none(),
                THE_TENTH));

        assertThat(assessment.sustainableDaily()).isEqualTo(Money.ZERO);
        assertThat(assessment.remainingBudget()).isEqualTo(Money.of(-300));
        assertThat(assessment.verdict()).isEqualTo(SpendVerdict.OVER_BUDGET);
    }

    @Test
    void aRequestWithImpossibleFiguresIsRejectedRatherThanAnswered() {
        TicketEstimate lunch = TicketEstimate.stated("Lunch", "a sit-down lunch", Money.of(25));

        assertThatThrownBy(() -> new SpendDecisionRequest(
                        SpendCategory.DINING_OUT,
                        Money.of(-1),
                        Money.ZERO,
                        lunch,
                        PriceLadder.none(),
                        THE_TENTH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowance");

        assertThatThrownBy(() -> new SpendDecisionRequest(
                        SpendCategory.DINING_OUT,
                        ALLOWANCE,
                        Money.of(-1),
                        lunch,
                        PriceLadder.none(),
                        THE_TENTH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spentMonthToDate");

        assertThatThrownBy(() -> TicketEstimate.stated("Lunch", "a sit-down lunch", Money.of(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
    }

    /**
     * The verdict and the price basis both reach the user directly, so both have to be sayable. A
     * person shown OVER_BUDGET, or told a figure is ESTIMATED, is being handed the codebase's
     * vocabulary instead of an answer.
     */
    @Test
    void everyVerdictAndPriceBasisExplainsItselfWithoutInternalVocabulary() {
        for (SpendVerdict verdict : SpendVerdict.values()) {
            assertThat(verdict.label()).isNotBlank().doesNotContain(verdict.name());
            assertThat(verdict.meaning())
                    .isNotBlank()
                    .doesNotContain(verdict.name(), "sustainableDaily", "discretionary", "baseline");
        }
        for (PriceBasis basis : PriceBasis.values()) {
            assertThat(basis.label()).isNotBlank().doesNotContain(basis.name());
            assertThat(basis.meaning()).isNotBlank().doesNotContain(basis.name());
        }
    }

    private static SpendAssessment decide(Money price) {
        return SpendDecision.decide(new SpendDecisionRequest(
                SpendCategory.DINING_OUT,
                ALLOWANCE,
                SPENT,
                TicketEstimate.stated("Lunch", "a sit-down lunch", price),
                PriceLadder.none(),
                THE_TENTH));
    }
}
