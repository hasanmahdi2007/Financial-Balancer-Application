package com.hasan.budget.costofliving.support;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.Contribution;
import com.hasan.budget.costofliving.domain.ContributionState;
import com.hasan.budget.costofliving.port.ContributionStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The contribution queue in memory, wired to a city-data source so that publishing is observable.
 *
 * <p>Publishing writes the contribution and the city default together, exactly as the Postgres
 * adapter does in one transaction. Without the second write a test would prove only that a row
 * changed state, and the behaviour that actually matters - that other users now see the figure - is
 * the half that would go unchecked.
 */
public final class InMemoryContributionStore implements ContributionStore {

    private static final String SOURCE_NAME = "Shared by another user of this app";

    private final Map<Long, Contribution> saved = new LinkedHashMap<>();
    private final InMemoryCostOfLivingProvider cityData;
    private long nextId = 1;

    public InMemoryContributionStore(InMemoryCostOfLivingProvider cityData) {
        this.cityData = cityData;
    }

    @Override
    public Contribution save(Contribution contribution) {
        long id = contribution.id() == Contribution.UNSAVED ? nextId++ : contribution.id();
        Contribution stored = new Contribution(
                id,
                contribution.userId(),
                contribution.country(),
                contribution.metro(),
                contribution.cityLabel(),
                contribution.category(),
                contribution.amount(),
                contribution.submittedAt(),
                contribution.state(),
                contribution.corroboration(),
                contribution.notes());
        saved.put(id, stored);
        return stored;
    }

    @Override
    public Optional<Contribution> find(long id) {
        return Optional.ofNullable(saved.get(id));
    }

    @Override
    public List<Contribution> queued() {
        List<Contribution> out = new ArrayList<>();
        saved.values().stream()
                .filter(c -> c.state() == ContributionState.QUEUED)
                .forEach(out::add);
        return List.copyOf(out);
    }

    @Override
    public Contribution publish(Contribution approved) {
        cityData.with(
                approved.listedCity().orElseThrow(),
                approved.category(),
                approved.amount(),
                Confidence.CONTRIBUTED,
                SOURCE_NAME,
                approved.submittedAt());
        return save(approved);
    }
}
