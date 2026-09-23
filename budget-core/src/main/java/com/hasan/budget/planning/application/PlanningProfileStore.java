package com.hasan.budget.planning.application;

import java.util.Optional;

/** Where the profile and the money a plan works with are kept. Every method is scoped to one user. */
public interface PlanningProfileStore {

    Optional<PlanningProfile> profile(String userId);

    void save(PlanningProfile profile);

    Optional<StatedMoney> money(String userId);

    void saveMoney(String userId, StatedMoney money);
}
