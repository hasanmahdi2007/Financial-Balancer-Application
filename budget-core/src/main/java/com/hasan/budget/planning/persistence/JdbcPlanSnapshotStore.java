package com.hasan.budget.planning.persistence;

import com.hasan.budget.planning.application.PlanKey;
import com.hasan.budget.planning.application.PlanSnapshotStore;
import com.hasan.budget.planning.application.PlanView;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Snapshots as JSON, one row per plan ever shown, each filed under the plan it was made for. There is
 * an INSERT here and no UPDATE or DELETE, and the table would refuse either.
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
    public void append(PlanKey plan, PlanView view) {
        jdbc.sql("""
                        INSERT INTO plan_snapshot (id, user_id, plan_id, taken_at, reason, body)
                        VALUES (:id, :userId, :planId, :takenAt, :reason, CAST(:body AS jsonb))
                        """)
                .param("id", view.id())
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .param("takenAt", Timestamp.from(view.takenAt()))
                .param("reason", view.reason())
                .param("body", json.writeValueAsString(view))
                .update();
    }

    @Override
    public Optional<PlanView> latest(PlanKey plan) {
        return jdbc.sql("""
                        SELECT body::text AS body
                          FROM plan_snapshot
                         WHERE user_id = :userId AND plan_id = :planId
                         ORDER BY seq DESC
                         LIMIT 1
                        """)
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .query((rs, row) -> json.readValue(rs.getString("body"), PlanView.class))
                .optional();
    }

    @Override
    public List<PlanView> all(PlanKey plan) {
        return jdbc.sql("""
                        SELECT body::text AS body
                          FROM plan_snapshot
                         WHERE user_id = :userId AND plan_id = :planId
                         ORDER BY seq DESC
                        """)
                .param("userId", plan.userId())
                .param("planId", plan.planId())
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
