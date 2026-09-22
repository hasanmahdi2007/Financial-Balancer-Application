package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * What the rest of the month looks like if the purchase is made anyway.
 *
 * <p>This is the half of the feature that makes it useful rather than merely disapproving. Following
 * it lands on the allowance rather than near it: the remaining daily rate is rounded <em>down</em>,
 * so the days together never add up to more than what is left.
 *
 * @param reducedDailyRate what each of the following days may spend
 * @param reductionPerDay how much lower that is than the rate before this purchase, which is the
 *     figure worth showing - "drop the next few days by 30 cents each" is actionable in a way that a
 *     new absolute rate is not
 * @param days how many days the reduced rate covers. Zero on the last day of the month, when there
 *     are no following days at all, which is exactly the case that would otherwise divide by zero.
 * @param fitsThisMonth false when no amount of cutting the remaining days can bring the month back
 *     inside the allowance. The honest answer is then that recovery runs past the horizon the user
 *     asked about, not a plan that appears to work.
 * @param spillsIntoNextMonth what is still over the allowance once the remaining days have been cut
 *     as far as they go, which is zero whenever the plan fits
 */
public record CatchUpPlan(
        Money reducedDailyRate,
        Money reductionPerDay,
        int days,
        boolean fitsThisMonth,
        Money spillsIntoNextMonth) {

    public CatchUpPlan {
        Objects.requireNonNull(reducedDailyRate, "reducedDailyRate");
        Objects.requireNonNull(reductionPerDay, "reductionPerDay");
        Objects.requireNonNull(spillsIntoNextMonth, "spillsIntoNextMonth");
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative but was " + days);
        }
    }

    /**
     * What the whole rest of the month costs if this plan is followed: the purchase itself plus the
     * reduced rate on every following day.
     *
     * <p>Worth asserting against the money actually left, because "lands exactly on the allowance"
     * is the promise this record makes and it is a rounding change away from being false.
     */
    public Money totalIfFollowed(Money price) {
        Objects.requireNonNull(price, "price");
        return price.plus(reducedDailyRate.times(days));
    }
}
