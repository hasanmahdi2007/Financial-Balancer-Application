package com.hasan.budget.costofliving.port;

import com.hasan.budget.costofliving.domain.CityBaselines;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.util.Optional;

/**
 * Answers what a place costs.
 *
 * <p>A port with two real implementations rather than one imagined one: a classpath adapter reading
 * committed CSVs, which keeps the pure tests runnable with no database, and a JPA adapter reading
 * Postgres. Both must satisfy the same shared contract test - that is what makes this port honest
 * rather than decorative. A regional source replacing BEA for a market Plaid and BEA do not cover is
 * then a new class, not an edit to every caller.
 *
 * <p>Implementations answer for the <em>place</em> only. Layering the user's own overrides on top is
 * the resolver's job in the application layer, so that swapping the data source never means
 * reimplementing override semantics.
 *
 * <p>Note the return type: never a bare {@code Money}. A bare number loses where it came from, and
 * the UI then cannot honestly distinguish a government statistic from a guess.
 *
 * <p><strong>Contract frozen by P0. Implementations belong to packet P2.</strong>
 */
public interface CostOfLivingProvider {

    /**
     * Empty when this source holds nothing for that city and category, so that a later layer in the
     * resolver chain can answer instead of the caller seeing a zero.
     */
    Optional<ResolvedBaseline> baselineFor(MetroId metro, SpendCategory category, IncomeQuintile quintile);

    /** Every category this source can answer for the given city, resolved as one set. */
    CityBaselines baselinesFor(MetroId metro, IncomeQuintile quintile);
}
