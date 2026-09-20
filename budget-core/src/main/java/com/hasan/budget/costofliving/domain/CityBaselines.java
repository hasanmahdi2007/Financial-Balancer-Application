package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every baseline for one city, resolved together.
 *
 * <p>Resolved as a set rather than one category at a time because the UI shows them together, and
 * because staleness is judged on the whole picture: a plan built from figures of very different ages
 * is worth flagging even when each individual number looks acceptable on its own.
 *
 * @param oldestAsOf the age of the weakest figure in the set, which is the honest age of any plan
 *     built from it
 */
public record CityBaselines(
        MetroId metro, Map<SpendCategory, ResolvedBaseline> baselines, LocalDate oldestAsOf) {

    public CityBaselines {
        Objects.requireNonNull(metro, "metro");
        Objects.requireNonNull(oldestAsOf, "oldestAsOf");
        baselines = Map.copyOf(baselines);
    }

    public Optional<ResolvedBaseline> forCategory(SpendCategory category) {
        return Optional.ofNullable(baselines.get(category));
    }

    /** True when any figure in the set has drifted far enough to be worth asking the user about. */
    public boolean hasStaleFigures() {
        return baselines.values().stream().anyMatch(b -> b.staleness() == Staleness.STALE);
    }
}
