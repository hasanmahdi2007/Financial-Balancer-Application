package com.hasan.budget.planning.persistence;

import com.hasan.budget.planning.application.PlanningProfile;
import com.hasan.budget.planning.application.PlanningProfileStore;
import com.hasan.budget.planning.application.StatedMoney;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Profiles and typed money, one row each per user, every statement keyed by the user id. */
@Repository
public class JdbcPlanningProfileStore implements PlanningProfileStore {

    private final JdbcClient jdbc;

    public JdbcPlanningProfileStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlanningProfile> profile(String userId) {
        return jdbc.sql("""
                        SELECT country_code, city_slug, city_not_listed, lifestyle, income_arrives_taxed,
                               least_for_enjoying_life
                          FROM planning_profile
                         WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query((rs, row) -> {
                    String city = rs.getString("city_slug");
                    String lifestyle = rs.getString("lifestyle");
                    BigDecimal least = rs.getBigDecimal("least_for_enjoying_life");
                    return new PlanningProfile(
                            userId,
                            new CountryCode(rs.getString("country_code")),
                            city == null ? null : new MetroId(city),
                            rs.getString("city_not_listed"),
                            lifestyle == null ? null : LifestyleTier.valueOf(lifestyle),
                            rs.getBoolean("income_arrives_taxed"),
                            least == null ? null : new Money(least));
                })
                .optional();
    }

    @Override
    public void save(PlanningProfile profile) {
        jdbc.sql("""
                        INSERT INTO planning_profile (user_id, country_code, city_slug, city_not_listed, lifestyle,
                                                      income_arrives_taxed, least_for_enjoying_life, updated_at)
                        VALUES (:userId, :country, :city, :notListed, :lifestyle, :taxed, :least, now())
                        ON CONFLICT (user_id) DO UPDATE
                           SET country_code            = EXCLUDED.country_code,
                               city_slug               = EXCLUDED.city_slug,
                               city_not_listed         = EXCLUDED.city_not_listed,
                               lifestyle               = EXCLUDED.lifestyle,
                               income_arrives_taxed    = EXCLUDED.income_arrives_taxed,
                               least_for_enjoying_life = EXCLUDED.least_for_enjoying_life,
                               updated_at              = now()
                        """)
                .param("userId", profile.userId())
                .param("country", profile.country().value())
                .param("city", profile.city() == null ? null : profile.city().slug())
                .param("notListed", profile.cityNotListed())
                .param("lifestyle", profile.lifestyle() == null ? null : profile.lifestyle().name())
                .param("taxed", profile.incomeArrivesTaxed())
                .param("least", profile.leastForEnjoyingLife() == null ? null : profile.leastForEnjoyingLife().amount())
                .update();
    }

    @Override
    public Optional<StatedMoney> money(String userId) {
        return jdbc.sql("SELECT monthly_income, balance, already_saving FROM planning_money WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, row) -> new StatedMoney(
                        new Money(rs.getBigDecimal("monthly_income")),
                        new Money(rs.getBigDecimal("balance")),
                        rs.getBigDecimal("already_saving") == null ? null : new Money(rs.getBigDecimal("already_saving"))))
                .optional();
    }

    @Override
    public void saveMoney(String userId, StatedMoney money) {
        jdbc.sql("""
                        INSERT INTO planning_money (user_id, monthly_income, balance, already_saving, updated_at)
                        VALUES (:userId, :income, :balance, :saving, now())
                        ON CONFLICT (user_id) DO UPDATE
                           SET monthly_income = EXCLUDED.monthly_income,
                               balance        = EXCLUDED.balance,
                               already_saving = EXCLUDED.already_saving,
                               updated_at     = now()
                        """)
                .param("userId", userId)
                .param("income", money.monthlyIncome().amount())
                .param("balance", money.balance().amount())
                .param("saving", money.alreadySaving() == null ? null : money.alreadySaving().amount())
                .update();
    }
}
