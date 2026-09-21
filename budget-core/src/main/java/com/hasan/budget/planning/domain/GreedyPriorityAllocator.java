package com.hasan.budget.planning.domain;

import com.hasan.budget.shared.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Allocates a monthly surplus across goals by descending priority, then by ascending monthly
 * requirement.
 *
 * <p>The surplus is a single divisible resource, so this is not a multi-dimensional knapsack: for
 * the objective "maximise the priority-weighted count of goals kept on pace", greedy allocation in
 * this order is optimal. Exchange argument: given a funded goal and an unfunded goal that outranks
 * it, moving the funding to the higher-ranked goal never reduces the priority-weighted count, so
 * some optimal solution agrees with the greedy choice at every step. Cost is O(n log n), dominated
 * by the sort.
 *
 * <p>This solves a single month in isolation. Multi-period planning, where a goal under-funded now
 * can be caught up later, is a different problem and would need a different strategy.
 */
public final class GreedyPriorityAllocator implements AllocationStrategy {

    @Override
    public AllocationResult allocate(AllocationRequest request) {
        List<Funding> ranked = rank(request);

        Money available = request.monthlySurplus().max(Money.ZERO);
        List<GoalAllocation> allocations = new ArrayList<>(ranked.size());

        for (Funding funding : ranked) {
            Money granted = available.min(funding.required());
            available = available.minus(granted);
            allocations.add(new GoalAllocation(
                    funding.goal().id(),
                    funding.goal().name(),
                    funding.goal().priority(),
                    funding.required(),
                    granted,
                    statusOf(funding.required(), granted)));
        }

        Money shortfall = allocations.stream()
                .map(GoalAllocation::shortfall)
                .reduce(Money.ZERO, Money::plus);

        // A negative surplus means essentials already outrun income, so cuts have to cover that
        // overspend on top of whatever the goals are short.
        Money deficit = request.monthlySurplus().isNegative()
                ? Money.ZERO.minus(request.monthlySurplus())
                : Money.ZERO;

        CutPlan cuts = suggestCuts(request.discretionary(), shortfall.plus(deficit));

        return new AllocationResult(allocations, available, cuts.tradeoffs(), cuts.residualGap());
    }

    private record Funding(GoalInput goal, Money required) {}

    /** The cuts on offer, and what they still fail to cover. */
    private record CutPlan(List<Tradeoff> tradeoffs, Money residualGap) {}

    private static List<Funding> rank(AllocationRequest request) {
        // Ascending requirement within a priority tier: funding the cheapest goals first keeps the
        // most goals on pace. Largest-first would starve several small goals to serve one big one.
        Comparator<Funding> order = Comparator
                .comparingInt((Funding funding) -> funding.goal().priority().weight())
                .reversed()
                .thenComparing((Funding funding) -> funding.required().amount())
                .thenComparing((Funding funding) -> funding.goal().id());

        return request.goals().stream()
                .map(goal -> new Funding(goal, goal.requiredMonthly(request.asOf())))
                .sorted(order)
                .toList();
    }

    private static AllocationStatus statusOf(Money required, Money granted) {
        if (required.isZero()) {
            return AllocationStatus.COMPLETED;
        }
        if (granted.compareTo(required) >= 0) {
            return AllocationStatus.ON_TRACK;
        }
        return granted.isPositive() ? AllocationStatus.AT_RISK : AllocationStatus.INFEASIBLE;
    }

    private static CutPlan suggestCuts(List<DiscretionarySpend> discretionary, Money needed) {
        if (!needed.isPositive()) {
            return new CutPlan(List.of(), Money.ZERO);
        }

        // Rigidity first, so what the user called disposable goes before what they called essential
        // and anything they locked is not touched at all. The category's cut order only breaks ties
        // inside a tier.
        List<DiscretionarySpend> byFlexibility = discretionary.stream()
                .filter(DiscretionarySpend::isCuttable)
                .sorted(Comparator
                        .comparing((DiscretionarySpend spend) -> spend.rigidity())
                        .thenComparingInt((DiscretionarySpend spend) -> spend.category().cutOrder())
                        .thenComparing((DiscretionarySpend spend) -> spend.category().name()))
                .toList();

        List<Tradeoff> cuts = new ArrayList<>();
        Money outstanding = needed;
        for (DiscretionarySpend spend : byFlexibility) {
            if (!outstanding.isPositive()) {
                break;
            }
            Money cut = spend.monthlyAmount().min(outstanding);
            if (cut.isPositive()) {
                cuts.add(new Tradeoff(spend.category(), cut));
                outstanding = outstanding.minus(cut);
            }
        }
        // Whatever is left once every cuttable line has been taken to zero. Locked lines and the
        // categories the engine refuses to name are deliberately not counted as available here, so
        // this is the gap the user really still has to close some other way.
        return new CutPlan(cuts, outstanding);
    }
}
