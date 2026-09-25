package com.hasan.budget.planning.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where a user's plans, and the money they plan with, are kept.
 *
 * <p>A plan is a place and the life the user leads there, so a plan and its profile are one record.
 * The money splits across the two: what arrives each month belongs to the plan, because a move usually
 * changes it, while the balance and what they already save belong to the person, because money in an
 * account moves with its owner.
 */
public interface PlanningProfileStore {

    /** @param lastUsedAt when this plan was last active or last recomputed */
    record StoredPlan(PlanKey key, PlanningProfile profile, Instant lastUsedAt) {}

    /** The plan everything else acts on, or empty before the user has told us where they live. */
    Optional<PlanKey> active(String userId);

    /** Makes this plan the active one and records it as used now. */
    void activate(PlanKey plan);

    /** Every plan this user has, most recently used first. */
    List<StoredPlan> plans(String userId);

    Optional<PlanningProfile> profile(PlanKey plan);

    /** Creates the plan when it does not exist yet, and replaces its profile when it does. */
    void save(PlanKey plan, PlanningProfile profile);

    /** Empty until both the plan's income and the person's balance are known. */
    Optional<StatedMoney> money(PlanKey plan);

    void saveMoney(PlanKey plan, StatedMoney money);
}
