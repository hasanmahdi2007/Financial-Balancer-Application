package com.hasan.budget.planning.application;

import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A goal as the user states it: what, how much, by when, and how much it matters.
 *
 * <p><strong>There is no "already saved" field, and that absence is the design.</strong> What a goal
 * already has is worked out from the considered balance by {@link Earmarking}, and nowhere else. If a
 * goal could also carry a typed "already saved: $20,000", the same dollars could reach it twice - once
 * typed and once earmarked - and the plan would read as funded when it is not. That error is
 * optimistic, which is the worst direction: nothing looks wrong, the plan is simply a lie. Leaving the
 * field out makes the double count impossible by construction instead of by a rule somebody has to
 * remember.
 *
 * <p>The domain's {@code GoalInput} does carry {@code saved}; it is filled in exactly once, by the
 * assembler, from the earmark.
 */
public record GoalDraft(String id, String name, Money target, LocalDate deadline, Priority priority) {

    public GoalDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(priority, "priority");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a goal needs a name the user will recognise");
        }
        if (!target.isPositive()) {
            throw new IllegalArgumentException("a goal's target must be more than zero, but was " + target);
        }
    }
}
