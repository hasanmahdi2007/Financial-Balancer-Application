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
 * @param tier how much of the user's life happens outside the house, or null when they have not
 *     been asked yet or skipped onboarding. Null is a real state rather than a defensive check: the
 *     tier is what sizes the protection, so without one there is no honest way to claim a
 *     particular figure, and the calculator falls back to the least it would leave anyone.
 * @param localBaselines the resolved baseline for each protected category in the user's city, as
 *     bare amounts. Provenance deliberately does not travel this far - the moment the arithmetic
 *     can see a confidence, someone writes a condition on it. May be empty, in which case the
 *     national fallback shares apply.
 * @param userStatedFloor the figure the user gave when asked, or null if they have not been asked
 *     or declined. When present it wins outright: they were asked a plain question about their own
 *     life and a computed default has no standing to argue with the answer.
 * @param cityLabel what to call the user's city when explaining the suggestion, or null when they
 *     have no known city. Display only, never a lookup key.
 * @param declaredCommitments discretionary spending the user has named and that nothing else has
 *     counted - a season ticket, a standing hobby payment - summed per category by the caller.
 *     <p>These exist because the surplus formula counts every discretionary category at zero and
 *     leaves the floor to cover them all in one figure. That works for observed spending and fails
 *     for a declared commitment: a $300 season ticket changes the surplus by nothing at all, and the
 *     app quietly tells the user it is free. A floor derived only from lifestyle tier and obligation
 *     load cannot know it was declared, so it has to be told.
 *     <p>Deliberately a map of plain amounts rather than the line items themselves. The floor needs
 *     one number per category and nothing else; taking the item type would point {@code profile} at
 *     {@code planning}, which already depends on this package for the floor, and a cycle between two
 *     modules is a worse price than a caller doing its own filtering. <strong>Only commitments that
 *     are subtracted in their own right belong here.</strong> An item that merely names part of a
 *     total already counted must be left out, or the same money raises the floor twice.
 */
public record FloorRequest(
        LifestyleTier tier,
        Money netMonthlyIncome,
        Money fixedCommitments,
        Map<SpendCategory, Money> localBaselines,
        Money userStatedFloor,
        String cityLabel,
        Map<SpendCategory, Money> declaredCommitments) {

    public FloorRequest {
        Objects.requireNonNull(netMonthlyIncome, "netMonthlyIncome");
        Objects.requireNonNull(fixedCommitments, "fixedCommitments");
        localBaselines = Map.copyOf(localBaselines);
        declaredCommitments = Map.copyOf(declaredCommitments);
        declaredCommitments.forEach((category, amount) -> {
            if (amount.isNegative()) {
                throw new IllegalArgumentException(
                        "a declared commitment must not be negative but " + category + " was " + amount);
            }
        });
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
        Objects.requireNonNull(tier, "tier");
        return new FloorRequest(
                tier, netMonthlyIncome, fixedCommitments, Map.of(), null, null, Map.of());
    }

    /**
     * A user who skipped onboarding, or reached a plan before being asked how often they go out.
     *
     * <p>Named rather than reached by passing a null tier, so that a caller arrives here on purpose.
     * The alternative - having onboarding pick a tier on the user's behalf - would put a policy
     * decision in whichever caller happened to need one first, and each caller would pick
     * differently.
     */
    public static FloorRequest withoutALifestyleTier(Money netMonthlyIncome, Money fixedCommitments) {
        return new FloorRequest(
                null, netMonthlyIncome, fixedCommitments, Map.of(), null, null, Map.of());
    }

    public FloorRequest in(String cityLabel, Map<SpendCategory, Money> localBaselines) {
        return new FloorRequest(
                tier,
                netMonthlyIncome,
                fixedCommitments,
                localBaselines,
                userStatedFloor,
                cityLabel,
                declaredCommitments);
    }

    /**
     * The user answered the question. Their figure replaces the computed default entirely - but not
     * money they have separately told us leaves their account every month, which is arithmetic
     * rather than opinion.
     */
    public FloorRequest statedBy(Money userStatedFloor) {
        return new FloorRequest(
                tier,
                netMonthlyIncome,
                fixedCommitments,
                localBaselines,
                userStatedFloor,
                cityLabel,
                declaredCommitments);
    }

    /**
     * Discretionary commitments the user has named, summed per category by the caller.
     *
     * <p>Pass only the ones subtracted in their own right. An item that merely names part of a total
     * already counted belongs nowhere near this map.
     */
    public FloorRequest withDeclaredCommitments(Map<SpendCategory, Money> declaredCommitments) {
        return new FloorRequest(
                tier,
                netMonthlyIncome,
                fixedCommitments,
                localBaselines,
                userStatedFloor,
                cityLabel,
                declaredCommitments);
    }

    /** Everything the user has named, which the floor may never fall below. */
    public Money totalDeclaredCommitments() {
        return declaredCommitments.values().stream().reduce(Money.ZERO, Money::plus);
    }

    /** How much of this user's income is already committed before any lifestyle spending. */
    public Rate obligationRatio() {
        return Rate.ratioOf(fixedCommitments, netMonthlyIncome);
    }
}
