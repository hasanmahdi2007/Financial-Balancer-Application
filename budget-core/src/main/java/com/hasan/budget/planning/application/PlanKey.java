package com.hasan.budget.planning.application;

import java.util.Objects;

/**
 * One plan of one user: the key every per-plan store takes.
 *
 * <p>The user id comes first and is always part of the key, so a plan id on its own reaches nothing.
 * That is what keeps "user A cannot read user B's plan" a property of every query rather than a check
 * someone has to remember - the same way a goal was never found by its own id alone.
 */
public record PlanKey(String userId, String planId) {

    public PlanKey {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(planId, "planId");
    }
}
