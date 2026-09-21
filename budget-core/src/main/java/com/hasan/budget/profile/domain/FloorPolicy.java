package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.SpendCategory;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rows that decide how much of a user's quality of life the engine may not touch.
 *
 * <p>Every number the floor depends on is here, loaded from tables, because this is policy rather
 * than arithmetic: it will be argued about, tuned against real users, and changed. Holding it as
 * constants in the calculator would make each of those a code change and a redeploy.
 *
 * <p>The shares are expressed against a category's <em>local baseline</em> rather than as dollars,
 * which is what makes one row work for Beirut and San Francisco at once. Adding a country needs new
 * baseline rows and no new code at all.
 *
 * @param protectedShareOfBaseline per tier, the fraction of each protected category's local
 *     baseline that the engine may not cut below. A fraction rather than the whole figure, because
 *     a floor equal to typical spending would make no cut possible anywhere.
 * @param obligationBands the ladder scaling that fraction by how much of the user's income is
 *     already committed. Ascending, with exactly one unbounded band last.
 * @param typicalShareOfNetIncome the fallback used where a city baseline is missing, from BLS CEX
 *     2024 national shares. Households spend close to their net income, so treating a share of
 *     expenditure as a share of net income is a stated approximation, not a hidden one.
 * @param floorAtLeastShareOfIncome the lower clamp, so a heavily indebted user is never told to
 *     live on nothing
 * @param floorAtMostShareOfIncome the upper clamp, so the floor cannot swallow the surplus and make
 *     every goal look infeasible
 */
public record FloorPolicy(
        Map<LifestyleTier, Map<SpendCategory, Rate>> protectedShareOfBaseline,
        List<ObligationBand> obligationBands,
        Map<SpendCategory, Rate> typicalShareOfNetIncome,
        Rate floorAtLeastShareOfIncome,
        Rate floorAtMostShareOfIncome) {

    public FloorPolicy {
        Objects.requireNonNull(floorAtLeastShareOfIncome, "floorAtLeastShareOfIncome");
        Objects.requireNonNull(floorAtMostShareOfIncome, "floorAtMostShareOfIncome");
        protectedShareOfBaseline = deepCopy(protectedShareOfBaseline);
        obligationBands = List.copyOf(obligationBands);
        typicalShareOfNetIncome = Map.copyOf(typicalShareOfNetIncome);

        List<SpendCategory> protectedCategories = ProtectedSpending.categories();
        for (LifestyleTier tier : LifestyleTier.values()) {
            Map<SpendCategory, Rate> shares = protectedShareOfBaseline.get(tier);
            if (shares == null || !shares.keySet().containsAll(protectedCategories)) {
                throw new IllegalArgumentException(
                        "the floor policy has no share for " + tier + " covering " + protectedCategories
                                + "; a missing row would silently stop protecting a category");
            }
        }
        if (!typicalShareOfNetIncome.keySet().containsAll(protectedCategories)) {
            throw new IllegalArgumentException(
                    "the floor policy has no fallback share for every one of " + protectedCategories);
        }
        requireLadderWithoutGaps(obligationBands);
        if (floorAtLeastShareOfIncome.compareTo(floorAtMostShareOfIncome) > 0) {
            throw new IllegalArgumentException(
                    "the floor cannot be clamped to at least " + floorAtLeastShareOfIncome
                            + " and at most " + floorAtMostShareOfIncome);
        }
    }

    /** The multiplier for a user whose commitments take this share of their income. */
    public Rate multiplierFor(Rate obligationRatio) {
        return obligationBands.stream()
                .filter(band -> band.covers(obligationRatio))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unreachable: the ladder is unbounded"))
                .multiplier();
    }

    public Rate protectedShare(LifestyleTier tier, SpendCategory category) {
        return protectedShareOfBaseline.get(tier).get(category);
    }

    private static void requireLadderWithoutGaps(List<ObligationBand> bands) {
        if (bands.isEmpty()) {
            throw new IllegalArgumentException("the obligation ladder must have at least one band");
        }
        for (int i = 0; i < bands.size() - 1; i++) {
            ObligationBand band = bands.get(i);
            if (band.isUnbounded()) {
                throw new IllegalArgumentException(
                        "an unbounded band at position " + i + " would swallow every band after it");
            }
            ObligationBand next = bands.get(i + 1);
            if (!next.isUnbounded()
                    && next.upToRatioOfIncome().compareTo(band.upToRatioOfIncome()) <= 0) {
                throw new IllegalArgumentException("obligation bands must ascend");
            }
        }
        if (!bands.getLast().isUnbounded()) {
            throw new IllegalArgumentException(
                    "the last obligation band must be unbounded, or a user above it would get no "
                            + "multiplier at all");
        }
    }

    private static Map<LifestyleTier, Map<SpendCategory, Rate>> deepCopy(
            Map<LifestyleTier, Map<SpendCategory, Rate>> source) {
        Map<LifestyleTier, Map<SpendCategory, Rate>> copy = new EnumMap<>(LifestyleTier.class);
        source.forEach((tier, shares) -> copy.put(tier, Map.copyOf(shares)));
        return Map.copyOf(copy);
    }
}
