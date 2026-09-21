package com.hasan.budget.costofliving.support;

import com.hasan.budget.costofliving.domain.UserOverride;
import com.hasan.budget.costofliving.port.UserOverrideStore;
import com.hasan.budget.shared.SpendCategory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A user-figure store with no database behind it, so the resolution tests stay in the fast tier. */
public final class InMemoryUserOverrideStore implements UserOverrideStore {

    private record Key(String userId, SpendCategory category) {}

    private final Map<Key, UserOverride> saved = new LinkedHashMap<>();

    @Override
    public Optional<UserOverride> find(String userId, SpendCategory category) {
        return Optional.ofNullable(saved.get(new Key(userId, category)));
    }

    @Override
    public Map<SpendCategory, UserOverride> findAll(String userId) {
        Map<SpendCategory, UserOverride> out = new LinkedHashMap<>();
        saved.forEach((key, value) -> {
            if (key.userId().equals(userId)) {
                out.put(key.category(), value);
            }
        });
        return Map.copyOf(out);
    }

    @Override
    public void save(UserOverride override) {
        saved.put(new Key(override.userId(), override.category()), override);
    }
}
