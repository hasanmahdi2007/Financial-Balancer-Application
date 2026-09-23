package com.hasan.budget.planning.persistence;

import com.hasan.budget.planning.application.GoalDraft;
import com.hasan.budget.planning.application.GoalStore;
import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.shared.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Goals and the finish-first nomination. No statement here can find a goal by its id alone. */
@Repository
public class JdbcGoalStore implements GoalStore {

    private static final String SELECT_GOALS = """
            SELECT id, name, target, deadline, priority
              FROM planning_goal
             WHERE user_id = :userId
            """;

    private final JdbcClient jdbc;

    public JdbcGoalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<GoalDraft> goals(String userId) {
        return jdbc.sql(SELECT_GOALS + " ORDER BY created_at, id")
                .param("userId", userId)
                .query((rs, row) -> new GoalDraft(
                        rs.getString("id"),
                        rs.getString("name"),
                        new Money(rs.getBigDecimal("target")),
                        rs.getDate("deadline").toLocalDate(),
                        Priority.valueOf(rs.getString("priority"))))
                .list();
    }

    @Override
    public Optional<GoalDraft> goal(String userId, String goalId) {
        return jdbc.sql(SELECT_GOALS + " AND id = :id")
                .param("userId", userId)
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
    public void save(String userId, GoalDraft goal) {
        jdbc.sql("""
                        INSERT INTO planning_goal (user_id, id, name, target, deadline, priority)
                        VALUES (:userId, :id, :name, :target, :deadline, :priority)
                        ON CONFLICT (user_id, id) DO UPDATE
                           SET name = EXCLUDED.name, target = EXCLUDED.target,
                               deadline = EXCLUDED.deadline, priority = EXCLUDED.priority
                        """)
                .param("userId", userId)
                .param("id", goal.id())
                .param("name", goal.name())
                .param("target", goal.target().amount())
                .param("deadline", goal.deadline())
                .param("priority", goal.priority().name())
                .update();
    }

    @Override
    public boolean delete(String userId, String goalId) {
        return jdbc.sql("DELETE FROM planning_goal WHERE user_id = :userId AND id = :id")
                        .param("userId", userId)
                        .param("id", goalId)
                        .update()
                > 0;
    }

    @Override
    public Optional<String> finishFirst(String userId) {
        return jdbc.sql("SELECT goal_id FROM planning_finish_first WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional();
    }

    @Override
    @Transactional
    public void setFinishFirst(String userId, Optional<String> goalId) {
        jdbc.sql("DELETE FROM planning_finish_first WHERE user_id = :userId").param("userId", userId).update();
        goalId.ifPresent(id -> jdbc.sql("INSERT INTO planning_finish_first (user_id, goal_id) VALUES (:userId, :goalId)")
                .param("userId", userId)
                .param("goalId", id)
                .update());
    }
}
