package com.hasan.budget.costofliving.port;

import com.hasan.budget.costofliving.domain.MetroId;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.shared.SpendCategory;
import java.util.Map;
import java.util.Optional;

/**
 * Answers what a place costs.
 *
 * <p>A port with two real implementations rather than one imagined one: a classpath adapter reading
 * committed CSVs, which keeps the pure tests runnable with no database, and a JPA adapter reading
 * Postgres. Both must satisfy the same shared contract test - that is what makes this port honest
 * rather than decorative. A regional source replacing BEA later is then a class, not an edit.
 *
 * <p>Implementations answer for the <em>place</em> only. Layering the user's own overrides on top is
 * the resolver's job in the application layer, so that swapping the data source never means
 * reimplementing override semantics.
 */
public interface CostOfLivingProvider {

    /** Empty when this source holds nothing for that city and category, so a later layer can answer. */
    Optional<ResolvedBaseline> baselineFor(MetroId metro, SpendCategory category, int incomeQuintile);

    /** Every category this source can answer for the given city. */
    Map<SpendCategory, ResolvedBaseline> baselinesFor(MetroId metro, int incomeQuintile);
}
