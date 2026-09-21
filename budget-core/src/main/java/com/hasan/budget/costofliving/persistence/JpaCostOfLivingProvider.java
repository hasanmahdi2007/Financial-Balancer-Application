package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.CityBaselines;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.StalenessPolicy;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Postgres adapter for city figures.
 *
 * <p>It is the second implementation of the same port as the classpath adapter, and the pair is
 * what makes that port honest. Both run the same contract test, so a behaviour that quietly depends
 * on the database - a different answer when a quintile is null, say, or a different tie-break
 * between two confidences - fails in one of them rather than being discovered by a user.
 */
@Repository
class JpaCostOfLivingProvider implements CostOfLivingProvider {

    private final MetroAreaRepository metros;
    private final CityCategoryBaselineRepository baselines;
    private final CountryRepository countries;
    private final DataSourceRepository sources;
    private final Clock clock;

    JpaCostOfLivingProvider(
            MetroAreaRepository metros,
            CityCategoryBaselineRepository baselines,
            CountryRepository countries,
            DataSourceRepository sources,
            Clock clock) {
        this.metros = metros;
        this.baselines = baselines;
        this.countries = countries;
        this.sources = sources;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResolvedBaseline> baselineFor(
            MetroId metro, SpendCategory category, IncomeQuintile quintile) {
        Optional<MetroAreaEntity> area = metros.findBySlug(metro.slug());
        if (area.isEmpty()) {
            return Optional.empty();
        }
        List<CityCategoryBaselineEntity> rows =
                baselines.findByMetroIdAndCategory(area.get().id(), category);
        return best(rows, quintile).map(row -> toBaseline(row, inflationOf(area.get())));
    }

    @Override
    @Transactional(readOnly = true)
    public CityBaselines baselinesFor(MetroId metro, IncomeQuintile quintile) {
        Optional<MetroAreaEntity> area = metros.findBySlug(metro.slug());
        if (area.isEmpty()) {
            return new CityBaselines(metro, Map.of(), LocalDate.now(clock));
        }
        int inflation = inflationOf(area.get());
        List<CityCategoryBaselineEntity> rows = baselines.findByMetroId(area.get().id());
        Map<SpendCategory, ResolvedBaseline> resolved = new EnumMap<>(SpendCategory.class);
        LocalDate oldest = null;
        for (SpendCategory category : SpendCategory.values()) {
            List<CityCategoryBaselineEntity> forCategory =
                    rows.stream().filter(r -> r.category() == category).toList();
            Optional<CityCategoryBaselineEntity> row = best(forCategory, quintile);
            if (row.isPresent()) {
                resolved.put(category, toBaseline(row.get(), inflation));
                LocalDate asOf = row.get().asOf();
                oldest = oldest == null || asOf.isBefore(oldest) ? asOf : oldest;
            }
        }
        return new CityBaselines(metro, resolved, oldest == null ? LocalDate.now(clock) : oldest);
    }

    /**
     * Same choice the classpath adapter makes, and it has to be: a row carrying the user's own
     * quintile wins, a row carrying none answers for everybody, and confidence breaks the remaining
     * tie in declaration order. Every curated row today has no quintile, so this path is the normal
     * one rather than a fallback.
     */
    private Optional<CityCategoryBaselineEntity> best(
            List<CityCategoryBaselineEntity> rows, IncomeQuintile quintile) {
        return rows.stream()
                .filter(r -> r.incomeQuintile() == null || r.incomeQuintile() == quintile)
                .min(Comparator.comparingInt(
                                (CityCategoryBaselineEntity r) -> r.incomeQuintile() == null ? 1 : 0)
                        .thenComparing(r -> r.confidence().ordinal()));
    }

    private ResolvedBaseline toBaseline(CityCategoryBaselineEntity row, int inflationBasisPoints) {
        return new ResolvedBaseline(
                new Money(row.monthlyAmount()),
                row.confidence(),
                sourceName(row.sourceId()),
                row.asOf(),
                StalenessPolicy.assess(row.asOf(), LocalDate.now(clock), inflationBasisPoints));
    }

    private String sourceName(String sourceId) {
        return sources.findById(sourceId)
                .map(DataSourceEntity::sourceName)
                .orElseThrow(() -> new IllegalStateException("figure references unknown source " + sourceId));
    }

    private int inflationOf(MetroAreaEntity area) {
        return countries.findById(area.countryCode())
                .map(CountryMapper::inflationBasisPoints)
                .orElseThrow(() -> new IllegalStateException(
                        "city " + area.slug() + " is in unknown country " + area.countryCode()));
    }
}
