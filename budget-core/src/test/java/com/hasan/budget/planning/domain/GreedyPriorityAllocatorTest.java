package com.hasan.budget.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class GreedyPriorityAllocatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 15);
    private static final LocalDate NEXT_MONTH = LocalDate.of(2026, 2, 15);

    private final GreedyPriorityAllocator allocator = new GreedyPriorityAllocator();

    @Test
    void fundsASingleGoalWhenTheSurplusCoversIt() {
        AllocationResult result = allocate(
                Money.of(1000),
                List.of(goal("car", "3000", NEXT_MONTH.plusMonths(2), Priority.MEDIUM)));

        GoalAllocation car = find(result, "car");
        assertThat(car.requiredMonthly()).isEqualTo(Money.of(1000));
        assertThat(car.allocated()).isEqualTo(Money.of(1000));
        assertThat(car.status()).isEqualTo(AllocationStatus.ON_TRACK);
        assertThat(result.unallocatedSurplus()).isEqualTo(Money.ZERO);
    }

    @Test
    void reportsLeftoverSurplusAsUnallocated() {
        AllocationResult result = allocate(
                Money.of(1000),
                List.of(goal("laptop", "400", NEXT_MONTH, Priority.MEDIUM)));

        assertThat(result.unallocatedSurplus()).isEqualTo(Money.of(600));
        assertThat(result.tradeoffs()).isEmpty();
    }

    @Test
    void returnsTheWholeSurplusWhenThereAreNoGoals() {
        AllocationResult result = allocate(Money.of(800), List.of());

        assertThat(result.goalAllocations()).isEmpty();
        assertThat(result.unallocatedSurplus()).isEqualTo(Money.of(800));
        assertThat(result.totalShortfall()).isEqualTo(Money.ZERO);
    }

    @Test
    void fundsHigherPriorityGoalsFirst() {
        AllocationResult result = allocate(
                Money.of(500),
                List.of(
                        goal("holiday", "500", NEXT_MONTH, Priority.LOW),
                        goal("emergency", "500", NEXT_MONTH, Priority.CRITICAL)));

        assertThat(find(result, "emergency").status()).isEqualTo(AllocationStatus.ON_TRACK);
        assertThat(find(result, "holiday").status()).isEqualTo(AllocationStatus.INFEASIBLE);
    }

    @Test
    void fundsCheapestGoalsFirstWithinAPriorityTier() {
        // Smallest-first keeps two goals on pace; largest-first would keep only one.
        AllocationResult result = allocate(
                Money.of(300),
                List.of(
                        goal("big", "300", NEXT_MONTH, Priority.MEDIUM),
                        goal("small", "100", NEXT_MONTH, Priority.MEDIUM),
                        goal("mid", "150", NEXT_MONTH, Priority.MEDIUM)));

        assertThat(find(result, "small").status()).isEqualTo(AllocationStatus.ON_TRACK);
        assertThat(find(result, "mid").status()).isEqualTo(AllocationStatus.ON_TRACK);
        assertThat(find(result, "big").status()).isEqualTo(AllocationStatus.AT_RISK);
        assertThat(find(result, "big").allocated()).isEqualTo(Money.of(50));
    }

    @Test
    void marksPartiallyFundedGoalsAtRiskWithAnExactShortfall() {
        AllocationResult result = allocate(
                Money.of(120),
                List.of(goal("bike", "500", NEXT_MONTH, Priority.HIGH)));

        GoalAllocation bike = find(result, "bike");
        assertThat(bike.status()).isEqualTo(AllocationStatus.AT_RISK);
        assertThat(bike.allocated()).isEqualTo(Money.of(120));
        assertThat(bike.shortfall()).isEqualTo(Money.of(380));
    }

    @Test
    void marksCompletelyUnfundedGoalsInfeasible() {
        AllocationResult result = allocate(
                Money.ZERO,
                List.of(goal("car", "3000", NEXT_MONTH, Priority.HIGH)));

        assertThat(find(result, "car").status()).isEqualTo(AllocationStatus.INFEASIBLE);
        assertThat(find(result, "car").allocated()).isEqualTo(Money.ZERO);
    }

    @Test
    void treatsAnAlreadyMetGoalAsCompletedAndSpendsNothingOnIt() {
        GoalInput met = new GoalInput(
                "met", "met", Money.of(500), Money.of(500), NEXT_MONTH, Priority.CRITICAL);

        AllocationResult result = allocate(
                Money.of(400),
                List.of(met, goal("pending", "400", NEXT_MONTH, Priority.LOW)));

        assertThat(find(result, "met").status()).isEqualTo(AllocationStatus.COMPLETED);
        assertThat(find(result, "met").allocated()).isEqualTo(Money.ZERO);
        assertThat(find(result, "pending").status()).isEqualTo(AllocationStatus.ON_TRACK);
    }

    @Test
    void countsOnlyTheUnsavedRemainderTowardTheMonthlyRequirement() {
        GoalInput partlySaved = new GoalInput(
                "car", "car", Money.of(1000), Money.of(700), NEXT_MONTH, Priority.MEDIUM);

        AllocationResult result = allocate(Money.of(1000), List.of(partlySaved));

        assertThat(find(result, "car").requiredMonthly()).isEqualTo(Money.of(300));
    }

    @Test
    void requiresTheFullRemainderWhenTheDeadlineHasAlreadyPassed() {
        AllocationResult result = allocate(
                Money.of(5000),
                List.of(goal("overdue", "2000", LocalDate.of(2025, 6, 1), Priority.HIGH)));

        assertThat(find(result, "overdue").requiredMonthly()).isEqualTo(Money.of(2000));
    }

    @Test
    void requiresTheFullRemainderWhenTheDeadlineIsThisMonth() {
        AllocationResult result = allocate(
                Money.of(5000),
                List.of(goal("urgent", "900", AS_OF.plusDays(10), Priority.HIGH)));

        assertThat(find(result, "urgent").requiredMonthly()).isEqualTo(Money.of(900));
    }

    @Test
    void spreadsAGoalAcrossItsRemainingMonths() {
        AllocationResult result = allocate(
                Money.of(5000),
                List.of(goal("car", "5000", AS_OF.plusMonths(5), Priority.HIGH)));

        assertThat(find(result, "car").requiredMonthly()).isEqualTo(Money.of(1000));
    }

    @Test
    void makesEveryGoalInfeasibleWhenThereIsNoSurplus() {
        AllocationResult result = allocate(
                Money.ZERO,
                List.of(
                        goal("a", "100", NEXT_MONTH, Priority.CRITICAL),
                        goal("b", "200", NEXT_MONTH, Priority.LOW)));

        assertThat(result.goalAllocations())
                .allMatch(allocation -> allocation.status() == AllocationStatus.INFEASIBLE);
        assertThat(result.totalShortfall()).isEqualTo(Money.of(300));
    }

    @Test
    void cutsMustAlsoCoverTheOverspendWhenTheSurplusIsNegative() {
        AllocationResult result = allocate(
                Money.of(-200),
                List.of(goal("car", "300", NEXT_MONTH, Priority.HIGH)),
                List.of(DiscretionarySpend.of(SpendCategory.ENTERTAINMENT, Money.of(600))));

        assertThat(find(result, "car").status()).isEqualTo(AllocationStatus.INFEASIBLE);
        // 300 shortfall on the goal plus 200 of overspend.
        assertThat(result.tradeoffs())
                .containsExactly(new Tradeoff(SpendCategory.ENTERTAINMENT, Money.of(500)));
    }

    @Test
    void takesCutsFromTheMostFlexibleCategoriesFirst() {
        AllocationResult result = allocate(
                Money.ZERO,
                List.of(goal("car", "250", NEXT_MONTH, Priority.HIGH)),
                List.of(
                        DiscretionarySpend.of(SpendCategory.SUBSCRIPTIONS, Money.of(300)),
                        DiscretionarySpend.of(SpendCategory.ENTERTAINMENT, Money.of(100))));

        assertThat(result.tradeoffs()).containsExactly(
                new Tradeoff(SpendCategory.ENTERTAINMENT, Money.of(100)),
                new Tradeoff(SpendCategory.SUBSCRIPTIONS, Money.of(150)));
    }

    @Test
    void cannotSuggestCuttingMoreThanIsActuallySpent() {
        AllocationResult result = allocate(
                Money.ZERO,
                List.of(goal("car", "1000", NEXT_MONTH, Priority.HIGH)),
                List.of(
                        DiscretionarySpend.of(SpendCategory.ENTERTAINMENT, Money.of(200)),
                        DiscretionarySpend.of(SpendCategory.SUBSCRIPTIONS, Money.of(100))));

        Money suggested = result.tradeoffs().stream()
                .map(Tradeoff::suggestedReduction)
                .reduce(Money.ZERO, Money::plus);
        assertThat(suggested).isEqualTo(Money.of(300));
    }

    @Test
    void suggestsNoCutsWhenEveryGoalIsOnTrack() {
        AllocationResult result = allocate(
                Money.of(1000),
                List.of(goal("car", "300", NEXT_MONTH, Priority.HIGH)),
                List.of(DiscretionarySpend.of(SpendCategory.ENTERTAINMENT, Money.of(400))));

        assertThat(result.tradeoffs()).isEmpty();
    }

    @Test
    void ordersIdenticalGoalsDeterministicallyById() {
        AllocationResult result = allocate(
                Money.of(150),
                List.of(
                        goal("zebra", "100", NEXT_MONTH, Priority.MEDIUM),
                        goal("apple", "100", NEXT_MONTH, Priority.MEDIUM)));

        assertThat(result.goalAllocations())
                .extracting(GoalAllocation::goalId)
                .containsExactly("apple", "zebra");
    }

    @Test
    void handlesManyCompetingGoalsWithoutLosingMoney() {
        List<GoalInput> goals = List.of(
                goal("a", "100", NEXT_MONTH, Priority.LOW),
                goal("b", "200", NEXT_MONTH, Priority.MEDIUM),
                goal("c", "300", NEXT_MONTH, Priority.HIGH),
                goal("d", "400", NEXT_MONTH, Priority.CRITICAL),
                goal("e", "500", NEXT_MONTH, Priority.MEDIUM));

        AllocationResult result = allocate(Money.of(1000), goals);

        Money distributed = result.goalAllocations().stream()
                .map(GoalAllocation::allocated)
                .reduce(Money.ZERO, Money::plus);
        assertThat(distributed.plus(result.unallocatedSurplus())).isEqualTo(Money.of(1000));
    }

    @Test
    void neverSuggestsCuttingSomethingTheUserLocked() {
        AllocationResult result = allocate(
                Money.ZERO,
                List.of(goal("car", "250", NEXT_MONTH, Priority.HIGH)),
                List.of(
                        new DiscretionarySpend(
                                SpendCategory.ENTERTAINMENT, Money.of(500), Rigidity.LOCKED),
                        DiscretionarySpend.of(SpendCategory.DINING_OUT, Money.of(100))));

        // The locked gym-membership case: the money is there, but the user has said it is untouchable,
        // so the engine takes what it can elsewhere and leaves the rest of the gap open.
        assertThat(result.tradeoffs())
                .containsExactly(new Tradeoff(SpendCategory.DINING_OUT, Money.of(100)));
    }

    @Test
    void prefersWhatTheUserCallsDisposableOverWhatTheyCallEssential() {
        AllocationResult result = allocate(
                Money.ZERO,
                List.of(goal("car", "150", NEXT_MONTH, Priority.HIGH)),
                List.of(
                        new DiscretionarySpend(
                                SpendCategory.DINING_OUT, Money.of(200), Rigidity.ESSENTIAL),
                        new DiscretionarySpend(
                                SpendCategory.CLOTHING, Money.of(200), Rigidity.DISPOSABLE)));

        // Rigidity beats the category's own default ordering, which would have put DINING_OUT first.
        assertThat(result.tradeoffs())
                .containsExactly(new Tradeoff(SpendCategory.CLOTHING, Money.of(150)));
    }

    @Test
    void neverSuggestsCuttingUnidentifiedSpending() {
        AllocationResult result = allocate(
                Money.ZERO,
                List.of(goal("car", "250", NEXT_MONTH, Priority.HIGH)),
                List.of(DiscretionarySpend.of(SpendCategory.OTHER, Money.of(900))));

        // "Reduce Other by $250" is advice nobody can act on, so the engine stays silent instead.
        assertThat(result.tradeoffs()).isEmpty();
    }

    private AllocationResult allocate(Money surplus, List<GoalInput> goals) {
        return allocate(surplus, goals, List.of());
    }

    private AllocationResult allocate(
            Money surplus, List<GoalInput> goals, List<DiscretionarySpend> discretionary) {
        return allocator.allocate(new AllocationRequest(surplus, goals, discretionary, AS_OF));
    }

    private static GoalInput goal(String id, String target, LocalDate deadline, Priority priority) {
        return new GoalInput(id, id, Money.of(target), Money.ZERO, deadline, priority);
    }

    private static GoalAllocation find(AllocationResult result, String goalId) {
        return result.goalAllocations().stream()
                .filter(allocation -> allocation.goalId().equals(goalId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no allocation for goal " + goalId));
    }
}
