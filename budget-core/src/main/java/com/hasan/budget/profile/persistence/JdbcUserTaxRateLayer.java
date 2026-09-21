package com.hasan.budget.profile.persistence;

import com.hasan.budget.profile.domain.Rate;
import com.hasan.budget.profile.domain.ResolvedTaxRate;
import com.hasan.budget.profile.port.TaxRateLayer;
import com.hasan.budget.shared.CountryCode;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Layer one: the rate the user typed for themselves.
 *
 * <p>Keyed on the user and nothing else. The country is ignored deliberately - someone who has told
 * us what they actually pay has answered the question for their own situation, and a move abroad is
 * a change they make, not one the app infers on their behalf.
 *
 * <p>Nothing here reads or writes a shared row, which is what keeps a typed rate private to the
 * account that typed it.
 */
@Repository
public class JdbcUserTaxRateLayer implements TaxRateLayer {

    private final JdbcClient jdbc;

    public JdbcUserTaxRateLayer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ResolvedTaxRate> rateFor(String userId, CountryCode country) {
        return jdbc.sql("SELECT effective_rate_bp, set_at FROM user_tax_override WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, row) -> ResolvedTaxRate.statedByUser(
                        new Rate(rs.getInt("effective_rate_bp")), rs.getDate("set_at").toLocalDate()))
                .optional();
    }

    /** Records what the user told us, replacing any earlier answer of their own. */
    public void record(String userId, Rate rate, LocalDate setAt) {
        jdbc.sql("""
                        INSERT INTO user_tax_override (user_id, effective_rate_bp, set_at)
                        VALUES (:userId, :rate, :setAt)
                        ON CONFLICT (user_id)
                        DO UPDATE SET effective_rate_bp = EXCLUDED.effective_rate_bp,
                                      set_at            = EXCLUDED.set_at
                        """)
                .param("userId", userId)
                .param("rate", rate.basisPoints())
                .param("setAt", setAt)
                .update();
    }
}
