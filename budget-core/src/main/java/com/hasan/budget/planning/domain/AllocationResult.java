package com.hasan.budget.planning.domain;

import com.hasan.budget.shared.Money;
import java.util.List;
import java.util.Objects;

/**
 * @param residualGap what the suggested cuts still leave uncovered, zero when they close the gap
 *     entirely. Reporting only the cuts invites the reader to assume the goal is now reachable;
 *     when every cuttable line has already been taken to zero and the plan is still short, saying
 *     so is the only honest answer, and it is what lets the caller offer the two real options
 *     instead — raise the considered share, or let a goal slip.
 */
public record AllocationResult(
        List<GoalAllocation> goalAllocations,
        Money unallocatedSurplus,
        List<Tradeoff> tradeoffs,
        Money residualGap) {

    public AllocationResult {
        Objects.requireNonNull(unallocatedSurplus, "unallocatedSurplus");
        Objects.requireNonNull(residualGap, "residualGap");
        goalAllocations = List.copyOf(goalAllocations);
        tradeoffs = List.copyOf(tradeoffs);
    }

    public Money totalShortfall() {
        return goalAllocations.stream()
                .map(GoalAllocation::shortfall)
                .reduce(Money.ZERO, Money::plus);
    }
}
