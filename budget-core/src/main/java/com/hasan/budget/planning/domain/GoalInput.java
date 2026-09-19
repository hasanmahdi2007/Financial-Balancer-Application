package com.hasan.budget.planning.domain;

import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record GoalInput(
        String id,
        String name,
        Money target,
        Money saved,
        LocalDate deadline,
        Priority priority) {

    public GoalInput {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(priority, "priority");
        if (target.isNegative()) {
            throw new IllegalArgumentException("target must not be negative but was " + target);
        }
        if (saved.isNegative()) {
            throw new IllegalArgumentException("saved must not be negative but was " + saved);
        }
    }

    public Money remaining() {
        return target.minus(saved).max(Money.ZERO);
    }

    /**
     * Whole months of runway, floored at one. A deadline in the current month or already in the
     * past both mean the full remaining amount is needed now rather than spread over future months.
     */
    public int monthsRemaining(LocalDate asOf) {
        long months = ChronoUnit.MONTHS.between(YearMonth.from(asOf), YearMonth.from(deadline));
        return (int) Math.max(1L, months);
    }

    public Money requiredMonthly(LocalDate asOf) {
        Money outstanding = remaining();
        return outstanding.isZero() ? Money.ZERO : outstanding.spreadOver(monthsRemaining(asOf));
    }
}
