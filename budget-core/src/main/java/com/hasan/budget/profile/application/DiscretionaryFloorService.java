package com.hasan.budget.profile.application;

import com.hasan.budget.profile.domain.DiscretionaryFloor;
import com.hasan.budget.profile.domain.DiscretionaryFloorCalculator;
import com.hasan.budget.profile.domain.FloorRequest;
import com.hasan.budget.profile.domain.SpendingQuestion;
import com.hasan.budget.profile.port.FloorPolicySource;
import java.util.Objects;

/**
 * Works out what to suggest when asking a user the least they would want to live on.
 *
 * <p>The split is deliberate: this class knows where the policy comes from, and
 * {@link DiscretionaryFloorCalculator} knows what to do with it. That is what lets every tier, band
 * and clamp be tested in milliseconds with no database, while the policy itself stays editable rows.
 */
public final class DiscretionaryFloorService {

    private final FloorPolicySource policySource;

    public DiscretionaryFloorService(FloorPolicySource policySource) {
        this.policySource = Objects.requireNonNull(policySource, "policySource");
    }

    public DiscretionaryFloor floorFor(FloorRequest request) {
        return new DiscretionaryFloorCalculator(policySource.load()).floorFor(request);
    }

    /**
     * The question to put to the user, pre-filled and explained.
     *
     * <p>Always asked, even though a figure could be derived silently. The derived number is a
     * starting point for a conversation about their life, not a decision taken on their behalf.
     */
    public SpendingQuestion questionFor(FloorRequest request) {
        return floorFor(request).asQuestion();
    }
}
