package com.hasan.budget.profile.persistence;

import com.hasan.budget.profile.domain.FloorPolicy;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.profile.domain.ObligationBand;
import com.hasan.budget.profile.domain.Rate;
import com.hasan.budget.profile.port.FloorPolicySource;
import com.hasan.budget.shared.SpendCategory;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the discretionary floor policy from the seeded tables.
 *
 * <p>Plain SQL rather than JPA on purpose. These are four small read-only lookup tables with no
 * identity, no lifetime and no relationships worth mapping; an entity per table would be more code
 * to change every time the policy shape moves, which is the one thing this packet expects to
 * happen. The mapping is small enough to read in full, which is the real test of whether an ORM is
 * earning its place.
 *
 * <p>The domain type validates what it is handed - a missing tier row, a gap in the obligation
 * ladder, an inverted clamp - so a half-seeded database fails loudly here rather than quietly
 * producing a floor of zero.
 */
@Repository
public class JdbcFloorPolicySource implements FloorPolicySource {

    private final JdbcClient jdbc;

    public JdbcFloorPolicySource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FloorPolicy load() {
        Clamp clamp = clamp();
        return new FloorPolicy(
                protectedShares(), obligationBands(), nationalShares(), clamp.atLeast(), clamp.atMost());
    }

    /** One row of {@code lifestyle_floor}, mapped before it is grouped. */
    private record TierShare(LifestyleTier tier, SpendCategory category, Rate share) {}

    private Map<LifestyleTier, Map<SpendCategory, Rate>> protectedShares() {
        List<TierShare> rows = jdbc.sql("SELECT tier, category, pct_of_baseline_bp FROM lifestyle_floor")
                .query((rs, row) -> new TierShare(
                        LifestyleTier.valueOf(rs.getString("tier")),
                        SpendCategory.valueOf(rs.getString("category")),
                        new Rate(rs.getInt("pct_of_baseline_bp"))))
                .list();

        Map<LifestyleTier, Map<SpendCategory, Rate>> shares = new EnumMap<>(LifestyleTier.class);
        for (TierShare row : rows) {
            shares.computeIfAbsent(row.tier(), tier -> new EnumMap<>(SpendCategory.class))
                    .put(row.category(), row.share());
        }
        return shares;
    }

    private List<ObligationBand> obligationBands() {
        return jdbc.sql("""
                        SELECT upper_ratio_bp, multiplier_bp
                        FROM floor_obligation_band
                        ORDER BY sort_rank
                        """)
                .query((rs, row) -> {
                    int upper = rs.getInt("upper_ratio_bp");
                    Rate upTo = rs.wasNull() ? null : new Rate(upper);
                    return new ObligationBand(upTo, new Rate(rs.getInt("multiplier_bp")));
                })
                .list();
    }

    /** One row of {@code discretionary_national_share}, mapped before it is grouped. */
    private record NationalShare(SpendCategory category, Rate share) {}

    private Map<SpendCategory, Rate> nationalShares() {
        List<NationalShare> rows = jdbc
                .sql("SELECT category, pct_of_net_income_bp FROM discretionary_national_share")
                .query((rs, row) -> new NationalShare(
                        SpendCategory.valueOf(rs.getString("category")),
                        new Rate(rs.getInt("pct_of_net_income_bp"))))
                .list();

        Map<SpendCategory, Rate> shares = new EnumMap<>(SpendCategory.class);
        rows.forEach(row -> shares.put(row.category(), row.share()));
        return shares;
    }

    private record Clamp(Rate atLeast, Rate atMost) {}

    private Clamp clamp() {
        return jdbc.sql("SELECT at_least_bp, at_most_bp FROM discretionary_floor_clamp")
                .query((rs, row) -> new Clamp(
                        new Rate(rs.getInt("at_least_bp")), new Rate(rs.getInt("at_most_bp"))))
                .single();
    }
}
