package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.BaselineQuery;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import java.util.Objects;
import java.util.Optional;

/**
 * A layer backed by city data, restricted to exactly one confidence tier.
 *
 * <p>One class covers the official, contributed and crowdsourced tiers, because the difference
 * between them is data and not behaviour. The filter on the way out is what keeps that true: a
 * source handing back a figure of the wrong tier is ignored rather than relabelled, so the official
 * slot cannot start quietly serving curated estimates the day somebody wires the wrong provider into
 * it.
 */
public final class CityDataLayer implements BaselineLayer {

    private final Confidence tier;
    private final CostOfLivingProvider source;

    public CityDataLayer(Confidence tier, CostOfLivingProvider source) {
        this.tier = Objects.requireNonNull(tier, "tier");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Confidence tier() {
        return tier;
    }

    @Override
    public Optional<ResolvedBaseline> lookup(BaselineQuery query) {
        return query.cityIfListed()
                .flatMap(metro -> source.baselineFor(metro, query.category(), query.quintile()))
                .filter(baseline -> baseline.confidence() == tier);
    }
}
