package com.hasan.budget.costofliving.support;

import com.hasan.budget.costofliving.domain.ManualLocation;
import com.hasan.budget.costofliving.port.UserLocationStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Manual locations with no database behind them, so the catalogue tests stay in the fast tier. */
public final class InMemoryUserLocationStore implements UserLocationStore {

    private final Map<String, ManualLocation> saved = new LinkedHashMap<>();

    @Override
    public Optional<ManualLocation> find(String userId) {
        return Optional.ofNullable(saved.get(userId));
    }

    @Override
    public void save(ManualLocation location) {
        saved.put(location.userId(), location);
    }
}
