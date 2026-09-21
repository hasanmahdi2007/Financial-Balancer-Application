package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.PlausibleBand;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.StalenessPolicy;
import com.hasan.budget.costofliving.port.CountryBaselineSource;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The Postgres adapter for country-level estimates and the bands that qualify them. */
@Repository
class JpaCountryBaselineSource implements CountryBaselineSource {

    private final NationalBaselineRepository nationalBaselines;
    private final CountryRepository countries;
    private final DataSourceRepository sources;
    private final Clock clock;

    JpaCountryBaselineSource(
            NationalBaselineRepository nationalBaselines,
            CountryRepository countries,
            DataSourceRepository sources,
            Clock clock) {
        this.nationalBaselines = nationalBaselines;
        this.countries = countries;
        this.sources = sources;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResolvedBaseline> countryBaseline(CountryCode country, SpendCategory category) {
        return row(country, category).map(entity -> toBaseline(entity, inflationOf(country)));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<SpendCategory, ResolvedBaseline> countryBaselines(CountryCode country) {
        int inflation = inflationOf(country);
        Map<SpendCategory, ResolvedBaseline> out = new EnumMap<>(SpendCategory.class);
        for (NationalBaselineEntity entity : nationalBaselines.findByCountryCode(country.value())) {
            out.put(entity.category(), toBaseline(entity, inflation));
        }
        return Map.copyOf(out);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlausibleBand> plausibleBand(CountryCode country, SpendCategory category) {
        return row(country, category).map(e -> new PlausibleBand(e.lowPct(), e.highPct()));
    }

    /**
     * Curated country rows carry no quintile, so the no-quintile row is the one that answers. A
     * later source may add quintile-specific rows beside it without this needing to change: the
     * quintile-free row stays the fallback for anyone those rows do not cover.
     */
    private Optional<NationalBaselineEntity> row(CountryCode country, SpendCategory category) {
        List<NationalBaselineEntity> rows =
                nationalBaselines.findByCountryCodeAndCategory(country.value(), category);
        return rows.stream().filter(e -> e.incomeQuintile() == null).findFirst()
                .or(() -> rows.stream().findFirst());
    }

    private ResolvedBaseline toBaseline(NationalBaselineEntity entity, int inflationBasisPoints) {
        return new ResolvedBaseline(
                new Money(entity.monthlyAmount()),
                entity.confidence(),
                sources.findById(entity.sourceId())
                        .map(DataSourceEntity::sourceName)
                        .orElseThrow(() -> new IllegalStateException(
                                "estimate references unknown source " + entity.sourceId())),
                entity.asOf(),
                StalenessPolicy.assess(entity.asOf(), LocalDate.now(clock), inflationBasisPoints));
    }

    private int inflationOf(CountryCode country) {
        return countries.findById(country.value())
                .map(CountryMapper::inflationBasisPoints)
                .orElseThrow(() -> new IllegalStateException("unknown country " + country.value()));
    }
}
