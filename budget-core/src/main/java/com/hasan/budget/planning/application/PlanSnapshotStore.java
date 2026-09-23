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

    void append(String userId, PlanView plan);

    Optional<PlanView> latest(String userId);

    /** Newest first. */
    List<PlanView> all(String userId);

    /** Empty when this user has no snapshot with that id - including when another user does. */
    Optional<PlanView> find(String userId, String snapshotId);
}
