package com.hasan.budget.costofliving.port;

import com.hasan.budget.costofliving.domain.UserOverride;
import com.hasan.budget.shared.SpendCategory;
import java.util.Map;
import java.util.Optional;

/**
 * Where a user's own figures live. Private to that user, always.
 *
 * <p>A port rather than a repository call from the service for one concrete reason: the resolution
 * chain is the piece most worth testing exhaustively, and it must be runnable with no database at
 * all. An in-memory implementation is what keeps those tests in the fast tier.
 */
public interface UserOverrideStore {

    Optional<UserOverride> find(String userId, SpendCategory category);

    Map<SpendCategory, UserOverride> findAll(String userId);

    void save(UserOverride override);
}
