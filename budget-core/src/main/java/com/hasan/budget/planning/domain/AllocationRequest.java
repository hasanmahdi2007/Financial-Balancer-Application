package com.hasan.budget.planning.domain;

import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * @param monthlySurplus income left after essentials; negative means essentials already exceed income
 */
public record AllocationRequest(
        Money monthlySurplus,
        List<GoalInput> goals,
        List<DiscretionarySpend> discretionary,
        LocalDate asOf) {

    public AllocationRequest {
        Objects.requireNonNull(monthlySurplus, "monthlySurplus");
        Objects.requireNonNull(asOf, "asOf");
        goals = List.copyOf(goals);
        discretionary = List.copyOf(discretionary);
    }
}
