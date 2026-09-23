package com.hasan.budget.planning.persistence;

import com.hasan.budget.planning.application.PlanSnapshotStore;
import com.hasan.budget.planning.application.PlanView;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Snapshots as JSON, one row per plan ever shown. There is an INSERT here and no UPDATE or DELETE,
 * and the table would refuse either.
 */
@Repository
public class JdbcPlanSnapshotStore implements PlanSnapshotStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcPlanSnapshotStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(String userId, PlanView plan) {
        jdbc.sql("""
                        INSERT INTO plan_snapshot (id, user_id, taken_at, reason, body)
                        VALUES (:id, :userId, :takenAt, :reason, CAST(:body AS jsonb))
                        """)
                .param("id", plan.id())
                .param("userId", userId)
                .param("takenAt", Timestamp.from(plan.takenAt()))
                .param("reason", plan.reason())
                .param("body", json.writeValueAsString(plan))
                .update();
    }

    @Override
    public Optional<PlanView> latest(String userId) {
        return jdbc.sql("""
                        SELECT body::text AS body
                          FROM plan_snapshot
                         WHERE user_id = :userId
                         ORDER BY seq DESC
                         LIMIT 1
                        """)
                .param("userId", userId)
                .query((rs, row) -> json.readValue(rs.getString("body"), PlanView.class))
                .optional();
    }

    @Override
    public List<PlanView> all(String userId) {
        return jdbc.sql("""
                        SELECT body::text AS body
                          FROM plan_snapshot
                         WHERE user_id = :userId
                         ORDER BY seq DESC
                        """)
                .param("userId", userId)
                .query((rs, row) -> json.readValue(rs.getString("body"), PlanView.class))
                .list();
    }

    @Override
    public Optional<PlanView> find(String userId, String snapshotId) {
        return jdbc.sql("SELECT body::text AS body FROM plan_snapshot WHERE user_id = :userId AND id = :id")
                .param("userId", userId)
                .param("id", snapshotId)
                .query((rs, row) -> json.readValue(rs.getString("body"), PlanView.class))
                .optional();
    }
}
