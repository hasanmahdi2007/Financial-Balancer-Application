package com.hasan.budget.costofliving.classpath;

import com.hasan.budget.costofliving.classpath.CuratedDataset.CountryFigure;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.costofliving.domain.PlausibleBand;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.StalenessPolicy;
import com.hasan.budget.costofliving.port.CountryBaselineSource;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The country-level fallback and its plausible bands, read from the committed CSVs. */
public final class ClasspathCountryBaselineSource implements CountryBaselineSource {

    private final CuratedDataset dataset;
    private final Clock clock;

    public ClasspathCountryBaselineSource(CuratedDataset dataset, Clock clock) {
        this.dataset = Objects.requireNonNull(dataset, "dataset");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<ResolvedBaseline> countryBaseline(CountryCode country, SpendCategory category) {
        return figure(country, category).map(this::toBaseline);
    }

    @Override
    public Map<SpendCategory, ResolvedBaseline> countryBaselines(CountryCode country) {
        Map<SpendCategory, ResolvedBaseline> out = new EnumMap<>(SpendCategory.class);
        for (CountryFigure figure : dataset.countryFigures()) {
            if (figure.country().equals(country)) {
                out.put(figure.category(), toBaseline(figure));
            }
        }
        return Map.copyOf(out);
    }

    @Override
    public Optional<PlausibleBand> plausibleBand(CountryCode country, SpendCategory category) {
        return figure(country, category).map(CountryFigure::band);
    }

    private Optional<CountryFigure> figure(CountryCode country, SpendCategory category) {
        return dataset.countryFigures().stream()
                .filter(f -> f.country().equals(country) && f.category() == category)
                .findFirst();
    }

    private ResolvedBaseline toBaseline(CountryFigure figure) {
        int inflation = dataset.country(figure.country())
                .map(CountryProfile::annualInflationBasisPoints)
                .orElseThrow(() -> new IllegalStateException(
                        "seed data holds an estimate for unknown country " + figure.country().value()));
        return new ResolvedBaseline(
                figure.amount(),
                figure.confidence(),
                dataset.sourceName(figure.sourceId()),
                figure.asOf(),
                StalenessPolicy.assess(figure.asOf(), LocalDate.now(clock), inflation));
    }
}
