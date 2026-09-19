package com.hasan.budget.planning.domain;

import com.hasan.budget.shared.Money;
import java.util.List;
import java.util.Objects;

public record AllocationResult(
        List<GoalAllocation> goalAllocations,
        Money unallocatedSurplus,
        List<Tradeoff> tradeoffs) {

    public AllocationResult {
        Objects.requireNonNull(unallocatedSurplus, "unallocatedSurplus");
        goalAllocations = List.copyOf(goalAllocations);
        tradeoffs = List.copyOf(tradeoffs);
    }

    public Money totalShortfall() {
        return goalAllocations.stream()
                .map(GoalAllocation::shortfall)
                .reduce(Money.ZERO, Money::plus);
    }
}
