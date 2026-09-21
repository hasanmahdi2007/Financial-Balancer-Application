package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Map;
import java.util.Objects;

/**
 * Everything needed to work out the least a user should be left to live on.
 *
 * <p>A request object rather than five parameters for the same reason {@code AllocationRequest} is
 * one: the arguments are all money-ish and adjacent, and a caller swapping two of them would
 * produce a plausible wrong answer rather than a compile error.
 *
 * @param localBaselines the resolved baseline for each protected category in the user's city, as
 *     bare amounts. Provenance deliberately does not travel this far - the moment the arithmetic
 *     can see a confidence, someone writes a condition on it. May be empty, in which case the
 *     national fallback shares apply.
 * @param userStatedFloor the figure the user gave when asked, or null if they have not been asked
 *     or declined. When present it wins outright: they were asked a plain question about their own
 *     life and a computed default has no standing to argue with the answer.
 * @param cityLabel what to call the user's city when explaining the suggestion, or null when they
 *     have no known city. Display only, never a lookup key.
 */
public record FloorRequest(
        LifestyleTier tier,
        Money netMonthlyIncome,
        Money fixedCommitments,
        Map<SpendCategory, Money> localBaselines,
        Money userStatedFloor,
        String cityLabel) {

    public FloorRequest {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(netMonthlyIncome, "netMonthlyIncome");
        Objects.requireNonNull(fixedCommitments, "fixedCommitments");
        localBaselines = Map.copyOf(localBaselines);
        if (!netMonthlyIncome.isPositive()) {
            throw new IllegalArgumentException(
                    "the floor is a share of net income, so income must be positive but was "
                            + netMonthlyIncome);
        }
        if (fixedCommitments.isNegative()) {
            throw new IllegalArgumentException(
                    "fixedCommitments must not be negative but was " + fixedCommitments);
        }
        if (userStatedFloor != null && userStatedFloor.isNegative()) {
            throw new IllegalArgumentException(
                    "userStatedFloor must not be negative but was " + userStatedFloor);
        }
    }

    /** A user with no bank data and no city figures yet: the first-run case. */
    public static FloorRequest of(LifestyleTier tier, Money netMonthlyIncome, Money fixedCommitments) {
        return new FloorRequest(tier, netMonthlyIncome, fixedCommitments, Map.of(), null, null);
    }

    public FloorRequest in(String cityLabel, Map<SpendCategory, Money> localBaselines) {
        return new FloorRequest(
                tier, netMonthlyIncome, fixedCommitments, localBaselines, userStatedFloor, cityLabel);
    }

    /** The user answered the question. Their figure replaces the computed default entirely. */
    public FloorRequest statedBy(Money userStatedFloor) {
        return new FloorRequest(
                tier, netMonthlyIncome, fixedCommitments, localBaselines, userStatedFloor, cityLabel);
    }

    /** How much of this user's income is already committed before any lifestyle spending. */
    public Rate obligationRatio() {
        return Rate.ratioOf(fixedCommitments, netMonthlyIncome);
    }
}
