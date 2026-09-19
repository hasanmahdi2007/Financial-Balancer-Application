package com.hasan.budget.planning.domain;

import com.hasan.budget.shared.Money;

public record GoalAllocation(
        String goalId,
        String goalName,
        Priority priority,
        Money requiredMonthly,
        Money allocated,
        AllocationStatus status) {

    public Money shortfall() {
        return requiredMonthly.minus(allocated).max(Money.ZERO);
    }
}
