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
 * <p>Keyed on the user <em>and</em> the country. A rate is what someone pays in one place: a move
 * starts or resumes a plan for the new place, and a Lebanese rate carried into a US plan would fund
 * the tax reserve from a bill that does not apply there. Two plans in the same country share the
 * rate, because it is a fact about the person there, not about one plan.
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
        return jdbc.sql("""
                        SELECT effective_rate_bp, set_at FROM user_tax_override
                        WHERE user_id = :userId AND country_code = :country
                        """)
                .param("userId", userId)
                .param("country", country.value())
                .query((rs, row) -> ResolvedTaxRate.statedByUser(
                        new Rate(rs.getInt("effective_rate_bp")), rs.getDate("set_at").toLocalDate()))
                .optional();
    }

    /** Records what the user told us for one country, replacing any earlier answer of theirs there. */
    public void record(String userId, CountryCode country, Rate rate, LocalDate setAt) {
        jdbc.sql("""
                        INSERT INTO user_tax_override (user_id, country_code, effective_rate_bp, set_at)
                        VALUES (:userId, :country, :rate, :setAt)
                        ON CONFLICT (user_id, country_code)
                        DO UPDATE SET effective_rate_bp = EXCLUDED.effective_rate_bp,
                                      set_at            = EXCLUDED.set_at
                        """)
                .param("userId", userId)
                .param("country", country.value())
                .param("rate", rate.basisPoints())
                .param("setAt", setAt)
                .update();
    }
}
