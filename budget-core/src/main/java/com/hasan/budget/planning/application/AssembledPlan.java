package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.planning.application.CutCandidates.SuggestedCut;
import com.hasan.budget.planning.application.Earmarking.Earmarks;
import com.hasan.budget.planning.domain.AllocationResult;
import com.hasan.budget.planning.domain.surplus.SurplusBreakdown;
import com.hasan.budget.profile.domain.DiscretionaryFloor;
import com.hasan.budget.profile.domain.Runway;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A plan, with every figure it was derived from and the provenance of every figure that came from
 * data - but not yet worded for anyone. {@link PlanViews} does the wording.
 *
 * @param reductions what the surplus has already assumed the user will change, line by line. Shown
 *     beside {@code suggestedCuts} always, because the surplus is not money in hand.
 * @param assumedSpending categories neither the user nor a bank covered, which the plan took at
 *     their local figure
 * @param measuredSpending categories the user did not state and a connected bank did, taken as the
 *     bank recorded them. Kept apart from the assumed ones because the plan says something different
 *     about each: one is a figure read off the user's own account, the other is a city average.
 * @param provenance where each local figure came from. Reaches the view and never the arithmetic.
 */
public record AssembledPlan(
        PlanningInputs inputs,
        DiscretionaryFloor floor,
        SurplusBreakdown breakdown,
        Earmarks earmarks,
        AllocationResult allocation,
        List<SuggestedCut> suggestedCuts,
        List<Reduction> reductions,
        List<Hint> hints,
        Runway runway,
        Set<SpendCategory> assumedSpending,
        Set<SpendCategory> measuredSpending,
        Map<SpendCategory, ResolvedBaseline> provenance) {

    public AssembledPlan {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(breakdown, "breakdown");
        Objects.requireNonNull(earmarks, "earmarks");
        Objects.requireNonNull(allocation, "allocation");
        Objects.requireNonNull(runway, "runway");
        suggestedCuts = List.copyOf(suggestedCuts);
        reductions = List.copyOf(reductions);
        hints = List.copyOf(hints);
        assumedSpending = Set.copyOf(assumedSpending);
        measuredSpending = Set.copyOf(measuredSpending);
        provenance = Map.copyOf(provenance);
    }

    public Money suggestedTotal() {
        return suggestedCuts.stream().map(SuggestedCut::amount).reduce(Money.ZERO, Money::plus);
    }

    /** The reduction already assumed plus the cuts on top: everything the user must actually change. */
    public Money totalChange() {
        return breakdown.totalReductionNeeded(suggestedTotal());
    }

    /**
     * One change the surplus already counted on, e.g. groceries from $700 to $450.
     *
     * @param lineIds the lines this reduction applies to; several for the fun-money pool, which the
     *     floor covers as one figure rather than category by category
     */
    public record Reduction(String label, List<String> lineIds, Money from, Money to) {

        public Reduction {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            lineIds = List.copyOf(lineIds);
        }

        public Money by() {
            return from.minus(to);
        }
    }

    /** A sentence about a line the plan may not cut. Never a number, so it can never reach a total. */
    public record Hint(String lineId, String label, String hint) {

        public Hint {
            Objects.requireNonNull(lineId, "lineId");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(hint, "hint");
        }
    }
}
