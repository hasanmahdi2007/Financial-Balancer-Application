package com.hasan.budget.planning.persistence;

import com.hasan.budget.planning.application.GoalDraft;
import com.hasan.budget.planning.application.GoalStore;
import com.hasan.budget.planning.application.PlanKey;
import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.shared.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goals and the finish-first nomination, per plan. No statement here can find a goal by its id alone,
 * and every one names both the user and the plan.
 */
@Repository
public class JdbcGoalStore implements GoalStore {

    private static final String SELECT_GOALS = """
            SELECT id, name, target, deadline, priority
              FROM planning_goal
             WHERE user_id = :userId AND plan_id = :planId
            """;

    private final JdbcClient jdbc;

    public JdbcGoalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<GoalDraft> goals(PlanKey plan) {
        return jdbc.sql(SELECT_GOALS + " ORDER BY created_at, id")
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .query((rs, row) -> new GoalDraft(
                        rs.getString("id"),
                        rs.getString("name"),
                        new Money(rs.getBigDecimal("target")),
                        rs.getDate("deadline").toLocalDate(),
                        Priority.valueOf(rs.getString("priority"))))
                .list();
    }

    @Override
    public Optional<GoalDraft> goal(PlanKey plan, String goalId) {
        return jdbc.sql(SELECT_GOALS + " AND id = :id")
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .param("id", goalId)
                .query((rs, row) -> new GoalDraft(
                        rs.getString("id"),
                        rs.getString("name"),
                        new Money(rs.getBigDecimal("target")),
                        rs.getDate("deadline").toLocalDate(),
                        Priority.valueOf(rs.getString("priority"))))
                .optional();
    }

    @Override
    public void save(PlanKey plan, GoalDraft goal) {
        jdbc.sql("""
                        INSERT INTO planning_goal (user_id, plan_id, id, name, target, deadline, priority)
                        VALUES (:userId, :planId, :id, :name, :target, :deadline, :priority)
                        ON CONFLICT (user_id, plan_id, id) DO UPDATE
                           SET name = EXCLUDED.name, target = EXCLUDED.target,
                               deadline = EXCLUDED.deadline, priority = EXCLUDED.priority
                        """)
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .param("id", goal.id())
                .param("name", goal.name())
                .param("target", goal.target().amount())
                .param("deadline", goal.deadline())
                .param("priority", goal.priority().name())
                .update();
    }

    @Override
    public boolean delete(PlanKey plan, String goalId) {
        return jdbc.sql("DELETE FROM planning_goal WHERE user_id = :userId AND plan_id = :planId AND id = :id")
                        .param("userId", plan.userId())
                        .param("planId", plan.planId())
                        .param("id", goalId)
                        .update()
                > 0;
    }

    @Override
    public Optional<String> finishFirst(PlanKey plan) {
        return jdbc.sql("SELECT goal_id FROM planning_finish_first WHERE user_id = :userId AND plan_id = :planId")
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .query(String.class)
                .optional();
    }

    @Override
    @Transactional
    public void setFinishFirst(PlanKey plan, Optional<String> goalId) {
        jdbc.sql("DELETE FROM planning_finish_first WHERE user_id = :userId AND plan_id = :planId")
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .update();
        goalId.ifPresent(id -> jdbc.sql("""
                        INSERT INTO planning_finish_first (user_id, plan_id, goal_id)
                        VALUES (:userId, :planId, :goalId)
                        """)
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .param("goalId", id)
                .update());
    }
}
