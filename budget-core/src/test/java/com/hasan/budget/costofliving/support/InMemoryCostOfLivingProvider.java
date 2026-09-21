package com.hasan.budget.costofliving.support;

import com.hasan.budget.costofliving.domain.CityBaselines;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.Staleness;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A city-data source a test can fill one row at a time.
 *
 * <p>The resolution chain is the piece where a mistake is silent - a wrong layer wins, the plan
 * still balances, and nobody finds out. Testing it needs figures placed at exact tiers, which a
 * committed CSV cannot do and a database would make slow. This holds resolved baselines verbatim,
 * so each test says plainly what each layer is offering.
 */
public final class InMemoryCostOfLivingProvider implements CostOfLivingProvider {

    private record Cell(MetroId metro, SpendCategory category) {}

    private final Map<Cell, List<ResolvedBaseline>> figures = new LinkedHashMap<>();

    /** Adds a figure at a named tier, dated so that staleness and provenance are explicit. */
    public InMemoryCostOfLivingProvider with(
            MetroId metro,
            SpendCategory category,
            Money amount,
            Confidence confidence,
            String sourceName,
            LocalDate asOf) {
        figures.computeIfAbsent(new Cell(metro, category), key -> new java.util.ArrayList<>())
                .add(new ResolvedBaseline(amount, confidence, sourceName, asOf, Staleness.FRESH));
        return this;
    }

    @Override
    public Optional<ResolvedBaseline> baselineFor(
            MetroId metro, SpendCategory category, IncomeQuintile quintile) {
        return figures.getOrDefault(new Cell(metro, category), List.of()).stream()
                .min(Comparator.comparingInt(b -> b.confidence().ordinal()));
    }

    @Override
    public CityBaselines baselinesFor(MetroId metro, IncomeQuintile quintile) {
        Map<SpendCategory, ResolvedBaseline> resolved = new EnumMap<>(SpendCategory.class);
        LocalDate oldest = LocalDate.MAX;
        for (SpendCategory category : SpendCategory.values()) {
            Optional<ResolvedBaseline> hit = baselineFor(metro, category, quintile);
            if (hit.isPresent()) {
                resolved.put(category, hit.get());
                oldest = hit.get().asOf().isBefore(oldest) ? hit.get().asOf() : oldest;
            }
        }
        return new CityBaselines(
                metro, resolved, resolved.isEmpty() ? LocalDate.EPOCH : oldest);
    }
}
