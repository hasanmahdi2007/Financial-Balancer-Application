package com.hasan.budget.costofliving.classpath;

import com.hasan.budget.costofliving.classpath.CuratedDataset.CityFigure;
import com.hasan.budget.costofliving.domain.CityBaselines;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.StalenessPolicy;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The committed-CSV adapter: city figures with no database anywhere near them.
 *
 * <p>It exists for two reasons that both pay for themselves. It keeps the whole resolution chain
 * testable in the fast tier, with no container to start. And it is the second implementation that
 * makes {@link CostOfLivingProvider} an honest port rather than an interface with one implementation
 * and an aspiration - the shared contract test runs against this and against the JPA adapter, so a
 * behaviour that quietly depends on Postgres fails here.
 *
 * <p>Today's date arrives through a {@link Clock} rather than being read directly, so a test can
 * stand at any point in time and watch the same figure move from fresh to stale. Adapters may hold a
 * clock; domain code may not.
 */
public final class ClasspathCostOfLivingProvider implements CostOfLivingProvider {

    private final CuratedDataset dataset;
    private final Clock clock;
    private final Map<MetroId, List<CityFigure>> figuresByMetro;

    public ClasspathCostOfLivingProvider(CuratedDataset dataset, Clock clock) {
        this.dataset = Objects.requireNonNull(dataset, "dataset");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.figuresByMetro = dataset.cityFigures().stream()
                .collect(java.util.stream.Collectors.groupingBy(CityFigure::metro));
    }

    @Override
    public Optional<ResolvedBaseline> baselineFor(
            MetroId metro, SpendCategory category, IncomeQuintile quintile) {
        return bestFigure(metro, category, quintile).map(this::toBaseline);
    }

    @Override
    public CityBaselines baselinesFor(MetroId metro, IncomeQuintile quintile) {
        Map<SpendCategory, ResolvedBaseline> resolved = new EnumMap<>(SpendCategory.class);
        LocalDate oldest = null;
        for (SpendCategory category : SpendCategory.values()) {
            Optional<CityFigure> figure = bestFigure(metro, category, quintile);
            if (figure.isPresent()) {
                resolved.put(category, toBaseline(figure.get()));
                LocalDate asOf = figure.get().asOf();
                oldest = oldest == null || asOf.isBefore(oldest) ? asOf : oldest;
            }
        }
        return new CityBaselines(metro, resolved, oldest == null ? today() : oldest);
    }

    /**
     * Picks the row that answers best for this person.
     *
     * <p>A row carrying the user's own income quintile is preferred, and a row carrying none answers
     * for everybody. Every curated row is in that second group, so the whole catalogue today is
     * quintile-free - and resolution has to keep working when a later source starts filling that
     * column in for some cities and not others. Confidence breaks the remaining tie, in declaration
     * order, which is why the enum's order is documented as precedence.
     */
    private Optional<CityFigure> bestFigure(
            MetroId metro, SpendCategory category, IncomeQuintile quintile) {
        List<CityFigure> candidates = figuresByMetro.getOrDefault(metro, List.of()).stream()
                .filter(f -> f.category() == category)
                .filter(f -> f.quintile() == null || f.quintile() == quintile)
                .toList();
        return candidates.stream()
                .min(Comparator.comparingInt((CityFigure f) -> f.quintile() == null ? 1 : 0)
                        .thenComparing(f -> f.confidence().ordinal()));
    }

    private ResolvedBaseline toBaseline(CityFigure figure) {
        return new ResolvedBaseline(
                figure.amount(),
                figure.confidence(),
                dataset.sourceName(figure.sourceId()),
                figure.asOf(),
                StalenessPolicy.assess(figure.asOf(), today(), inflationOf(figure.metro())));
    }

    private int inflationOf(MetroId metro) {
        CuratedDataset.MetroRow row = dataset.metrosById().get(metro);
        if (row == null) {
            throw new IllegalStateException("seed data holds a figure for unknown city " + metro.slug());
        }
        return dataset.country(row.country())
                .map(CountryProfile::annualInflationBasisPoints)
                .orElseThrow(() -> new IllegalStateException(
                        "seed data holds a city in unknown country " + row.country().value()));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
