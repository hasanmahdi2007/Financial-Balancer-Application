package com.hasan.budget.planning.persistence;

import com.hasan.budget.planning.application.PlanKey;
import com.hasan.budget.planning.application.PlanningProfile;
import com.hasan.budget.planning.application.PlanningProfileStore;
import com.hasan.budget.planning.application.StatedMoney;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans, which are profiles with an id, and the money planned with. Every statement is keyed by the
 * user id, so no plan id reaches another user's row.
 */
@Repository
public class JdbcPlanningProfileStore implements PlanningProfileStore {

    private static final String SELECT_PLANS = """
            SELECT user_id, plan_id, country_code, city_slug, city_not_listed, lifestyle, income_arrives_taxed,
                   least_for_enjoying_life, last_used_at
              FROM planning_profile
             WHERE user_id = :userId
            """;

    private final JdbcClient jdbc;

    public JdbcPlanningProfileStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlanKey> active(String userId) {
        return jdbc.sql("SELECT plan_id FROM planning_active_plan WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, row) -> new PlanKey(userId, rs.getString("plan_id")))
                .optional();
    }

    @Override
    @Transactional
    public void activate(PlanKey plan) {
        // The foreign key on planning_active_plan refuses a plan this user does not own, so a forged
        // plan id fails here even if a caller forgot to check.
        jdbc.sql("""
                        INSERT INTO planning_active_plan (user_id, plan_id) VALUES (:userId, :planId)
                        ON CONFLICT (user_id) DO UPDATE SET plan_id = EXCLUDED.plan_id
                        """)
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .update();
        jdbc.sql("UPDATE planning_profile SET last_used_at = now() WHERE user_id = :userId AND plan_id = :planId")
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .update();
    }

    @Override
    public List<StoredPlan> plans(String userId) {
        return jdbc.sql(SELECT_PLANS + " ORDER BY last_used_at DESC, created_at DESC")
                .param("userId", userId)
                .query((rs, row) -> new StoredPlan(
                        new PlanKey(userId, rs.getString("plan_id")),
                        profileFrom(rs),
                        rs.getTimestamp("last_used_at").toInstant()))
                .list();
    }

    @Override
    public Optional<PlanningProfile> profile(PlanKey plan) {
        return jdbc.sql(SELECT_PLANS + " AND plan_id = :planId")
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .query((rs, row) -> profileFrom(rs))
                .optional();
    }

    @Override
    public void save(PlanKey plan, PlanningProfile profile) {
        jdbc.sql("""
                        INSERT INTO planning_profile (user_id, plan_id, country_code, city_slug, city_not_listed,
                                                      lifestyle, income_arrives_taxed, least_for_enjoying_life,
                                                      updated_at)
                        VALUES (:userId, :planId, :country, :city, :notListed, :lifestyle, :taxed, :least, now())
                        ON CONFLICT (user_id, plan_id) DO UPDATE
                           SET country_code            = EXCLUDED.country_code,
                               city_slug               = EXCLUDED.city_slug,
                               city_not_listed         = EXCLUDED.city_not_listed,
                               lifestyle               = EXCLUDED.lifestyle,
                               income_arrives_taxed    = EXCLUDED.income_arrives_taxed,
                               least_for_enjoying_life = EXCLUDED.least_for_enjoying_life,
                               updated_at              = now()
                        """)
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .param("country", profile.country().value())
                .param("city", profile.city() == null ? null : profile.city().slug())
                .param("notListed", profile.cityNotListed())
                .param("lifestyle", profile.lifestyle() == null ? null : profile.lifestyle().name())
                .param("taxed", profile.incomeArrivesTaxed())
                .param("least", profile.leastForEnjoyingLife() == null ? null : profile.leastForEnjoyingLife().amount())
                .update();
    }

    @Override
    public Optional<StatedMoney> money(PlanKey plan) {
        return jdbc.sql("""
                        SELECT p.monthly_income, m.balance, m.already_saving
                          FROM planning_profile p
                          JOIN planning_money m ON m.user_id = p.user_id
                         WHERE p.user_id = :userId AND p.plan_id = :planId AND p.monthly_income IS NOT NULL
                        """)
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .query((rs, row) -> new StatedMoney(
                        new Money(rs.getBigDecimal("monthly_income")),
                        new Money(rs.getBigDecimal("balance")),
                        rs.getBigDecimal("already_saving") == null ? null : new Money(rs.getBigDecimal("already_saving"))))
                .optional();
    }

    @Override
    @Transactional
    public void saveMoney(PlanKey plan, StatedMoney money) {
        jdbc.sql("""
                        UPDATE planning_profile SET monthly_income = :income, updated_at = now()
                         WHERE user_id = :userId AND plan_id = :planId
                        """)
                .param("userId", plan.userId())
                .param("planId", plan.planId())
                .param("income", money.monthlyIncome().amount())
                .update();
        jdbc.sql("""
                        INSERT INTO planning_money (user_id, balance, already_saving, updated_at)
                        VALUES (:userId, :balance, :saving, now())
                        ON CONFLICT (user_id) DO UPDATE
                           SET balance        = EXCLUDED.balance,
                               already_saving = EXCLUDED.already_saving,
                               updated_at     = now()
                        """)
                .param("userId", plan.userId())
                .param("balance", money.balance().amount())
                .param("saving", money.alreadySaving() == null ? null : money.alreadySaving().amount())
                .update();
    }

    private static PlanningProfile profileFrom(ResultSet rs) throws SQLException {
        String city = rs.getString("city_slug");
        String lifestyle = rs.getString("lifestyle");
        BigDecimal least = rs.getBigDecimal("least_for_enjoying_life");
        return new PlanningProfile(
                rs.getString("user_id"),
                new CountryCode(rs.getString("country_code")),
                city == null ? null : new MetroId(city),
                rs.getString("city_not_listed"),
                lifestyle == null ? null : LifestyleTier.valueOf(lifestyle),
                rs.getBoolean("income_arrives_taxed"),
                least == null ? null : new Money(least));
    }
}
