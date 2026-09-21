package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.BaselineQuery;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.port.CountryBaselineSource;
import java.util.Objects;
import java.util.Optional;

/**
 * The last layer, and the reason there is never a zero.
 *
 * <p>A city with no row for a category, or a user whose city is not in the catalogue at all, lands
 * here. Returning nothing would be read downstream as "this costs nothing", which is not a smaller
 * error than a rough estimate - it is a silent one that produces a confident, wrong plan.
 */
public final class CountryEstimateLayer implements BaselineLayer {

    private final CountryBaselineSource countryBaselines;

    public CountryEstimateLayer(CountryBaselineSource countryBaselines) {
        this.countryBaselines = Objects.requireNonNull(countryBaselines, "countryBaselines");
    }

    @Override
    public Confidence tier() {
        return Confidence.ESTIMATED;
    }

    @Override
    public Optional<ResolvedBaseline> lookup(BaselineQuery query) {
        return countryBaselines
                .countryBaseline(query.country(), query.category())
                .filter(baseline -> baseline.confidence() == Confidence.ESTIMATED);
    }
}
