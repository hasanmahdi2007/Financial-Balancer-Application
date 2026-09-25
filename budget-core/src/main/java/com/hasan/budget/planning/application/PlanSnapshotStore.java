package com.hasan.budget.planning.application;

import java.util.List;
import java.util.Optional;

/**
 * Plans as they were shown, kept forever.
 *
 * <p>Append-only by the shape of this interface: there is no method to change or remove a snapshot,
 * and the table behind the production implementation refuses both as well. Rewriting what a plan
 * said would make the history a record of today's opinion about the past.
 */
public interface PlanSnapshotStore {

    void append(PlanKey plan, PlanView view);

    Optional<PlanView> latest(PlanKey plan);

    /** Newest first, and only this plan's: the history of one place never mixes in another's. */
    List<PlanView> all(PlanKey plan);

    /**
     * Any of this user's snapshots, from whichever plan. Empty when they have none with that id -
     * including when another user does.
     */
    Optional<PlanView> find(String userId, String snapshotId);
}
