package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.CityBaselines;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * The empty official slot, kept deliberately occupied.
 *
 * <p>v1 ships curated figures only. Deriving baselines from BLS, BEA and Census is deferred, not
 * abandoned - the full specification is in
 * {@code .claude/packets/P9-DEFERRED-official-cost-of-living.md}. This class is what makes reviving
 * it a new class rather than a refactor: the layer is already wired, already ordered above
 * contributed and crowdsourced data, and already covered by a test that proves the chain falls
 * through it cleanly. Swapping this for a real source is one constructor argument.
 *
 * <p>Deleting it would be the mistake. An absent layer looks like a decision nobody made, and the
 * next person to add official data would have to work out where it belongs in the order.
 */
public final class NoOfficialData implements CostOfLivingProvider {

    /** Never read, and never zero-dated, because nothing here should look like a real figure. */
    private static final LocalDate NO_DATA_DATE = LocalDate.EPOCH;

    @Override
    public Optional<ResolvedBaseline> baselineFor(
            MetroId metro, SpendCategory category, IncomeQuintile quintile) {
        return Optional.empty();
    }

    @Override
    public CityBaselines baselinesFor(MetroId metro, IncomeQuintile quintile) {
        return new CityBaselines(metro, Map.of(), NO_DATA_DATE);
    }
}
