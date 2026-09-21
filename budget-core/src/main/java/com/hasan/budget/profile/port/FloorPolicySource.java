package com.hasan.budget.profile.port;

import com.hasan.budget.profile.domain.FloorPolicy;

/**
 * Supplies the rows that decide how much of a user's quality of life is protected.
 *
 * <p>A port because the policy is expected to move. Today it is seeded tables; the moment it is
 * tuned per country, or held behind a feature flag while two variants are compared, that becomes a
 * different source and no caller should notice. Keeping it behind an interface also lets the pure
 * floor tests state their own policy rather than standing up a database to read one.
 */
public interface FloorPolicySource {

    FloorPolicy load();
}
