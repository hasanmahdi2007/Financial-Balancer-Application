package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Moves money between the lines of a plan without changing its total.
 *
 * <p>Raising one line by an amount takes the same amount from the others, in rigidity order:
 * {@link Rigidity#DISPOSABLE} first, {@link Rigidity#ESSENTIAL} last, {@link Rigidity#LOCKED} never -
 * neither cut nor raised. Nothing goes below its floor. Where those rules mean the increase cannot be
 * found, the answer says so and offers the two real ways out, because a budget that balances only
 * because the engine overrode an instruction the user gave it is worse than one that admits it does not
 * balance.
 *
 * <p>Pure arithmetic over the lines it is handed, like the allocator and the affordability check. It
 * reads no clock, needs no container, and sees no city: the floors arrive already resolved.
 */
public final class Rebalancing {

    private Rebalancing() {}

    public static RebalanceResult apply(RebalanceRequest request) {
        Objects.requireNonNull(request, "request");

        List<SavingHint> hints = hintsFor(request);
        BudgetLine target = request.target();

        // Asked to raise something the user marked as unchangeable. Rebalancing respects that in both
        // directions, so the honest answer is about the mark, not about where the money would come from.
        if (!target.mayRise()) {
            return new RebalanceResult(
                    RebalanceOutcome.TARGET_IS_LOCKED, List.of(), Money.ZERO, request.increase(), hints, List.of());
        }

        List<Adjustment> adjustments = new ArrayList<>();
        Money outstanding = request.increase();
        for (BudgetLine donor : donorOrder(request.others())) {
            if (!outstanding.isPositive()) {
                break;
            }
            Money given = donor.headroom().min(outstanding);
            if (given.isPositive()) {
                adjustments.add(new Adjustment(
                        donor.id(), donor.label(), donor.category(), donor.amount(), donor.amount().minus(given)));
                outstanding = outstanding.minus(given);
            }
        }

        // The target only ever gains what the others actually gave up. Granting the full increase and
        // reporting the gap separately would hand the caller a set of lines that no longer sums to the
        // plan it started from, and the discrepancy would surface much later as a wrong surplus.
        Money granted = request.increase().minus(outstanding);
        if (granted.isPositive()) {
            adjustments.add(0, new Adjustment(
                    target.id(), target.label(), target.category(), target.amount(), target.amount().plus(granted)));
        }

        return new RebalanceResult(
                outcomeFor(granted, outstanding),
                adjustments,
                granted,
                outstanding,
                hints,
                outstanding.isPositive()
                        ? List.of(ResolutionOption.values())
                        : List.<ResolutionOption>of());
    }

    /**
     * Donors in the order the user themselves set. Rigidity decides, and the category's own cut order
     * only breaks ties inside a tier, so two things the user called equally disposable are taken in a
     * stable, stated order rather than in whatever order they happened to be listed.
     */
    private static List<BudgetLine> donorOrder(List<BudgetLine> others) {
        return others.stream()
                .filter(line -> line.headroom().isPositive())
                .sorted(Comparator.comparing(BudgetLine::rigidity)
                        .thenComparingInt(line -> line.category().cutOrder())
                        .thenComparing(BudgetLine::id))
                .toList();
    }

    /**
     * A hint for every line the engine may not cut, so the lines it stays silent about numerically are
     * not simply ignored. Note that it covers the target as well: a user who locked their gym and then
     * tried to raise it is precisely the person who needs to be told that off-peak memberships exist.
     */
    private static List<SavingHint> hintsFor(RebalanceRequest request) {
        return request.lines().stream()
                .filter(line -> !line.mayGive())
                .map(SavingHint::forLine)
                .toList();
    }

    private static RebalanceOutcome outcomeFor(Money granted, Money outstanding) {
        if (!outstanding.isPositive()) {
            return RebalanceOutcome.ABSORBED;
        }
        return granted.isPositive() ? RebalanceOutcome.PARTIALLY_ABSORBED : RebalanceOutcome.INFEASIBLE;
    }
}
