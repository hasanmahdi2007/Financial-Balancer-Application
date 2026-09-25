package com.hasan.budget.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** How far each goal has got, as the goals page shows it. */
class GoalProgressTest {

    @Test
    void progressIsTheShareOfTheTargetAlreadyCovered() {
        assertThat(PlanViews.percentCovered(Money.of(3000), Money.of(9000))).isEqualTo(33);
        assertThat(PlanViews.percentCovered(Money.of(4500), Money.of(9000))).isEqualTo(50);
        assertThat(PlanViews.percentCovered(Money.ZERO, Money.of(9000))).isZero();
    }

    /** Rounded down, so a goal never reads as done while a cent is still missing. */
    @Test
    void aGoalIsOnlyEverAHundredPercentWhenItIsFullyCovered() {
        assertThat(PlanViews.percentCovered(Money.of("8999.99"), Money.of(9000))).isEqualTo(99);
        assertThat(PlanViews.percentCovered(Money.of(9000), Money.of(9000))).isEqualTo(100);
    }

    @Test
    void neverOutsideZeroToAHundred() {
        assertThat(PlanViews.percentCovered(Money.of(12_000), Money.of(9000))).isEqualTo(100);
    }

    @Test
    void everyGoalOnAMadePlanCarriesItsProgress() {
        PlanningFixture fixture = new PlanningFixture(LocalDate.of(2026, 3, 14));
        PlanService plans = fixture.plans();
        fixture.onboard("saver", Money.of(3000));
        plans.addGoal("saver", new PlanService.GoalChange("Car", Money.of(9000), LocalDate.of(2027, 3, 1), Priority.HIGH));

        PlanView.Goal car = plans.latest("saver").orElseThrow().goals().getFirst();

        assertThat(car.fromBalance()).isEqualTo("3000.00");
        assertThat(car.percentCovered()).isEqualTo(33);
    }
}
