package com.hasan.budget.planning.application;

import java.util.List;
import java.util.Optional;

/**
 * A user's goals, and which one they nominated to take the balance first. Every method is scoped to
 * one user: a goal id alone never finds anything.
 */
public interface GoalStore {

    List<GoalDraft> goals(String userId);

    Optional<GoalDraft> goal(String userId, String goalId);

    void save(String userId, GoalDraft goal);

    /** @return false when this user has no goal with that id - including when another user does */
    boolean delete(String userId, String goalId);

    Optional<String> finishFirst(String userId);

    /** Empty clears the nomination, returning the balance to priority order. */
    void setFinishFirst(String userId, Optional<String> goalId);
}
