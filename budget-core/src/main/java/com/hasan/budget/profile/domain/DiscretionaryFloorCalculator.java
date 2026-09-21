package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Works out the least a user should be left to live on, from policy rows and nothing else.
 *
 * <pre>
 * protected(c) = typical(c) × protectedShareOfBaseline(tier, c)
 * derived      = Σ protected(c) × obligationMultiplier(fixedCommitments / netIncome)
 *                clamped to [3%, 12%] of net income
 * floor(c)     = max(derived share of c, what the user declared for c)
 * floor        = Σ floor(c)
 * </pre>
 *
 * <p>The last two lines are why a declared commitment cannot be lost. The surplus formula counts
 * every discretionary category at zero and leaves this one figure to cover them, which works for
 * observed spending and fails for something the user names: a declared season ticket would change
 * the surplus by nothing at all, and the app would be telling them it was free.
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
            // Declared commitments are the one thing it cannot fall below, because those are not an
            // opinion about their life; they are money leaving the account either way.
            return finish(
                    request, split(weights, request.userStatedFloor()), "the figure you set", true);
        }

        if (request.tier() == null) {
            // The least the policy would leave anyone, rather than a tier picked on the user's
            // behalf. Claiming "typical for someone who goes out regularly" about a person who has
            // never said how often they go out would be inventing the grounding, and a stated basis
            // that is not true is worse than no figure at all.
            Money leastForAnyone =
                    policy.floorAtLeastShareOfIncome().applyTo(request.netMonthlyIncome());
            return finish(
                    request,
                    split(weights, leastForAnyone),
                    "the least we protect for anyone, until you tell us how often you go out",
                    false);
        }

        Money computed = weights.values().stream().reduce(Money.ZERO, Money::plus);
        Money adjusted = policy.multiplierFor(request.obligationRatio()).applyTo(computed);
        Money clamped = clampToIncome(adjusted, request.netMonthlyIncome());
        return finish(request, split(weights, clamped), basisFor(request), false);
    }

    /**
     * Raises the derived protection to cover anything the user has actually declared, and reports
     * whether the result outgrew what the model considers sustainable.
     *
     * <p>Applied <em>after</em> the clamps, and that order is the whole point. The clamps bound a
     * guess - they stop a derived figure swallowing the surplus. A declared commitment is not a
     * guess: it is money the user has told us leaves every month. Clamping it away would leave the
     * surplus overstated by the difference, which is the same defect as counting the commitment at
     * zero, arrived at more politely.
     *
     * <p>Per category rather than against the total, so that a large commitment in one category
     * cannot crowd out the protection for the others. A $700 season ticket must not leave the
     * engine free to cut eating out to nothing on the grounds that the total is already high
     * enough.
     */
    private DiscretionaryFloor finish(
            FloorRequest request, Map<SpendCategory, Money> derived, String basis, boolean userProvided) {
        Map<SpendCategory, Money> perCategory = new EnumMap<>(SpendCategory.class);
        derived.forEach((category, amount) -> perCategory.put(
                category, amount.max(request.declaredCommitments().getOrDefault(category, Money.ZERO))));
        // A commitment parented to a category the floor does not otherwise protect still has to be
        // covered, or the surplus counts it at zero exactly as before.
        request.declaredCommitments()
                .forEach((category, amount) -> perCategory.merge(category, amount, Money::max));

        Money monthly = perCategory.values().stream().reduce(Money.ZERO, Money::plus);
        Money ceiling = policy.floorAtMostShareOfIncome().applyTo(request.netMonthlyIncome());
        return new DiscretionaryFloor(monthly, perCategory, basis, userProvided, ceiling);
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
            // Weight times total over the whole, rather than a rounded percentage of the total.
            // Going via a rate loses precision at the fourth decimal place, which showed up as a
            // split of an untouched total handing back 89.99 for a 90.00 weight.
            Money share = new Money(entry.getValue()
                    .amount()
                    .multiply(total.amount())
                    .divide(weighed.amount(), 10, RoundingMode.HALF_UP));
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
