package com.hasan.budget.profile.persistence;

import com.hasan.budget.profile.domain.Rate;
import com.hasan.budget.profile.domain.ResolvedTaxRate;
import com.hasan.budget.profile.port.TaxRateLayer;
import com.hasan.budget.shared.CountryCode;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Layer two: the seeded country estimate that every account shares until one of them overrides it.
 *
 * <p>Read-only by construction. There is no method here that writes, so a user's own figure has no
 * route into the shared row even by accident.
 *
 * <p>Empty for a country we hold no figure for, which is the signal to ask the user rather than to
 * invent one - a fabricated rate would fund a tax reserve out of money that has no bill behind it.
 */
@Repository
public class JdbcCountryTaxRateLayer implements TaxRateLayer {

    private final JdbcClient jdbc;

    public JdbcCountryTaxRateLayer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ResolvedTaxRate> rateFor(String userId, CountryCode country) {
        return jdbc.sql("""
                        SELECT effective_rate_bp, source_name, as_of
                        FROM country_tax_rate
                        WHERE country_code = :country
                        """)
                .param("country", country.value())
                .query((rs, row) -> ResolvedTaxRate.estimatedForCountry(
                        new Rate(rs.getInt("effective_rate_bp")),
                        rs.getString("source_name"),
                        rs.getDate("as_of").toLocalDate()))
                .optional();
    }
}
