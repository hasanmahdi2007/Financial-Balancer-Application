package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Works out the least a user should be left to live on, from policy rows and nothing else.
 *
 * <pre>
 * protected(c) = typical(c) × protectedShareOfBaseline(tier, c)
 * floor        = Σ protected(c) × obligationMultiplier(fixedCommitments / netIncome)
 *                clamped to [3%, 12%] of net income
 * </pre>
 *
 * <p>{@code typical(c)} is the user's own city baseline where one exists, which is what makes the
 * same policy row produce a Beirut figure in Beirut and a San Francisco figure in San Francisco.
 * Where the city is unknown it falls back to the BLS CEX national share of net income, so a user
 * gets a credible floor on their very first screen rather than a blank.
 *
 * <p>The multiplier is the honest part. A floor is a claim about what someone can protect, and
 * someone whose rent and loans already take 75% of their income cannot protect as much as someone
 * at 30%. The clamps bound that claim at both ends: the lower stops a heavily indebted user being
 * told to live on nothing, and the upper stops the floor swallowing the whole surplus and making
 * every goal look infeasible.
 *
 * <p>Pure, and deliberately so: no clock, no I/O and no framework, so every tier, every band and
 * both clamps can be exercised in milliseconds.
 */
public final class DiscretionaryFloorCalculator {

    private final FloorPolicy policy;

    public DiscretionaryFloorCalculator(FloorPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public DiscretionaryFloor floorFor(FloorRequest request) {
        Objects.requireNonNull(request, "request");

        // Without a tier there is nothing to size the protection with, so the weights are plain
        // typical spending and only the lower clamp applies.
        Map<SpendCategory, Money> weights = request.tier() == null
                ? typicalAmounts(request)
                : protectedAmounts(request);

        if (request.userStatedFloor() != null) {
            // Their answer replaces the default outright - no multiplier, no clamp. They were asked
            // a plain question about their own life, and a derived figure has no standing to argue.
            return new DiscretionaryFloor(
                    request.userStatedFloor(),
                    split(weights, request.userStatedFloor()),
                    "the figure you set",
                    true);
        }

        if (request.tier() == null) {
            // The least the policy would leave anyone, rather than a tier picked on the user's
            // behalf. Claiming "typical for someone who goes out regularly" about a person who has
            // never said how often they go out would be inventing the grounding, and a stated basis
            // that is not true is worse than no figure at all.
            Money leastForAnyone =
                    policy.floorAtLeastShareOfIncome().applyTo(request.netMonthlyIncome());
            return new DiscretionaryFloor(
                    leastForAnyone,
                    split(weights, leastForAnyone),
                    "the least we protect for anyone, until you tell us how often you go out",
                    false);
        }

        Money computed = weights.values().stream().reduce(Money.ZERO, Money::plus);
        Money adjusted = policy.multiplierFor(request.obligationRatio()).applyTo(computed);
        Money floor = clampToIncome(adjusted, request.netMonthlyIncome());
        return new DiscretionaryFloor(floor, split(weights, floor), basisFor(request), false);
    }

    /** What the user's city says each protected category normally costs, before any protection. */
    private Map<SpendCategory, Money> typicalAmounts(FloorRequest request) {
        Map<SpendCategory, Money> amounts = new EnumMap<>(SpendCategory.class);
        for (SpendCategory category : ProtectedSpending.categories()) {
            Money typical = request.localBaselines().get(category);
            if (typical == null) {
                typical = policy.typicalShareOfNetIncome()
                        .get(category)
                        .applyTo(request.netMonthlyIncome());
            }
            amounts.put(category, typical);
        }
        return amounts;
    }

    /** What each protected category is worth before the obligation multiplier and the clamps. */
    private Map<SpendCategory, Money> protectedAmounts(FloorRequest request) {
        Map<SpendCategory, Money> amounts = new EnumMap<>(SpendCategory.class);
        typicalAmounts(request).forEach((category, typical) ->
                amounts.put(category, policy.protectedShare(request.tier(), category).applyTo(typical)));
        return amounts;
    }

    private Money clampToIncome(Money floor, Money netMonthlyIncome) {
        return floor.max(policy.floorAtLeastShareOfIncome().applyTo(netMonthlyIncome))
                .min(policy.floorAtMostShareOfIncome().applyTo(netMonthlyIncome));
    }

    /**
     * Shares a total across categories in proportion to what each was worth, giving the rounding
     * remainder to the largest so that the parts always add back up to the whole. A split that does
     * not reconcile is the kind of cent-level discrepancy a user notices and nobody can explain.
     */
    private static Map<SpendCategory, Money> split(Map<SpendCategory, Money> weights, Money total) {
        Money weighed = weights.values().stream().reduce(Money.ZERO, Money::plus);
        Map<SpendCategory, Money> shares = new EnumMap<>(SpendCategory.class);
        if (!weighed.isPositive()) {
            weights.keySet().forEach(category -> shares.put(category, Money.ZERO));
            return shares;
        }

        Money allocated = Money.ZERO;
        for (Map.Entry<SpendCategory, Money> entry : weights.entrySet()) {
            Money share = Rate.ratioOf(entry.getValue(), weighed).applyTo(total);
            shares.put(entry.getKey(), share);
            allocated = allocated.plus(share);
        }

        SpendCategory largest = largestOf(weights);
        shares.put(largest, shares.get(largest).plus(total.minus(allocated)));
        return shares;
    }

    // Declaration order breaks a tie, so the same inputs always produce the same split.
    private static SpendCategory largestOf(Map<SpendCategory, Money> weights) {
        SpendCategory largest = null;
        for (Map.Entry<SpendCategory, Money> entry : weights.entrySet()) {
            if (largest == null || entry.getValue().compareTo(weights.get(largest)) > 0) {
                largest = entry.getKey();
            }
        }
        return Objects.requireNonNull(largest, "a floor always covers at least one category");
    }

    private String basisFor(FloorRequest request) {
        String someone = request.cityLabel() == null
                ? "someone "
                : "someone in " + request.cityLabel() + " ";
        return "typical for " + someone + request.tier().describesSomeone();
    }

}
