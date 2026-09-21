package com.hasan.budget.profile.domain;

import java.util.Objects;

/**
 * One row of the ladder that scales the discretionary floor by how loaded a user already is.
 *
 * <p>Someone whose rent and loan payments take 75% of their income cannot also protect a large
 * lifestyle budget; someone at 30% can. Expressed as rows rather than as a chain of conditions so
 * that changing the policy is editing data, not editing an algorithm.
 *
 * @param upToRatioOfIncome the top of this band, inclusive. Null on the final band, which catches
 *     everything above the one before it - a ladder with a gap at the top would silently produce no
 *     multiplier at all.
 */
public record ObligationBand(Rate upToRatioOfIncome, Rate multiplier) {

    public ObligationBand {
        Objects.requireNonNull(multiplier, "multiplier");
    }

    /** The final band: everything above the previous one. */
    public static ObligationBand above(Rate multiplier) {
        return new ObligationBand(null, multiplier);
    }

    public boolean isUnbounded() {
        return upToRatioOfIncome == null;
    }

    public boolean covers(Rate obligationRatio) {
        return isUnbounded() || obligationRatio.compareTo(upToRatioOfIncome) <= 0;
    }
}
