package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.UserOverride;
import com.hasan.budget.costofliving.port.UserOverrideStore;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Postgres adapter for a user's own figures.
 *
 * <p>Every query here is keyed by user id first. That is not a performance habit: these rows are
 * the most private thing this module stores, and a finder that can return another person's figure
 * is a finder somebody will eventually call by accident.
 */
@Repository
class JpaUserOverrideStore implements UserOverrideStore {

    private final UserCategoryOverrideRepository overrides;

    JpaUserOverrideStore(UserCategoryOverrideRepository overrides) {
        this.overrides = overrides;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserOverride> find(String userId, SpendCategory category) {
        return overrides
                .findById(new UserCategoryOverrideEntity.Key(userId, category))
                .map(JpaUserOverrideStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<SpendCategory, UserOverride> findAll(String userId) {
        Map<SpendCategory, UserOverride> out = new EnumMap<>(SpendCategory.class);
        for (UserCategoryOverrideEntity entity : overrides.findByUserId(userId)) {
            out.put(entity.category(), toDomain(entity));
        }
        return Map.copyOf(out);
    }

    @Override
    @Transactional
    public void save(UserOverride override) {
        overrides.save(new UserCategoryOverrideEntity(
                override.userId(),
                override.category(),
                override.amount().amount(),
                override.setAt(),
                override.corroboration()));
    }

    private static UserOverride toDomain(UserCategoryOverrideEntity entity) {
        return new UserOverride(
                entity.userId(),
                entity.category(),
                new Money(entity.monthlyAmount()),
                entity.setAt(),
                entity.corroboration());
    }
}
