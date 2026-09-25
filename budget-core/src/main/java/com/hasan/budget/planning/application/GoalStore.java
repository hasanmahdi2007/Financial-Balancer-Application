package com.hasan.budget.planning.application;

import java.util.List;
import java.util.Optional;

/**
 * The goals in one plan, and which one the user nominated to take the balance first. Every method is
 * scoped to one plan of one user: a goal id alone never finds anything.
 */
public interface GoalStore {

    List<GoalDraft> goals(PlanKey plan);

    Optional<GoalDraft> goal(PlanKey plan, String goalId);

    void save(PlanKey plan, GoalDraft goal);

    /** @return false when this user has no goal with that id - including when another user does */
    boolean delete(PlanKey plan, String goalId);

    Optional<String> finishFirst(PlanKey plan);

    /** Empty clears the nomination, returning the balance to priority order. */
    void setFinishFirst(PlanKey plan, Optional<String> goalId);
}
