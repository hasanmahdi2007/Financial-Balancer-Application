package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What rebalancing did, what it could not do, and what the user can do about the difference.
 *
 * @param granted how much the raised line actually gained, which is the whole increase only when the
 *     rest of the plan could cover it. The pool never grows, so a raise nobody paid for does not happen.
 * @param residualGap what is still missing. Zero when absorbed, and the figure the two options below
 *     exist to answer.
 * @param hints one per line the engine was not allowed to cut. Qualitative, and by construction unable
 *     to carry a number into any total.
 * @param options the two real ways out, present whenever {@code residualGap} is positive and empty when
 *     it is not. Offering them when nothing is missing would be noise; withholding them when something
 *     is missing would leave the user with a plan that does not add up and no way to say so.
 */
public record RebalanceResult(
        RebalanceOutcome outcome,
        List<Adjustment> adjustments,
        Money granted,
        Money residualGap,
        List<SavingHint> hints,
        List<ResolutionOption> options) {

    public RebalanceResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(granted, "granted");
        Objects.requireNonNull(residualGap, "residualGap");
        adjustments = List.copyOf(adjustments);
        hints = List.copyOf(hints);
        options = List.copyOf(options);
    }

    /**
     * Every adjustment added together, which must be exactly zero: money moved between lines, none was
     * created. It is the property most likely to catch a refactor of the walk that produced it, so it is
     * worth asserting rather than assuming.
     */
    public Money net() {
        return adjustments.stream().map(Adjustment::change).reduce(Money.ZERO, Money::plus);
    }

    /** The adjustment for one line, empty if that line did not move. */
    public Optional<Adjustment> adjustmentFor(String lineItemId) {
        return adjustments.stream().filter(adjustment -> adjustment.lineItemId().equals(lineItemId)).findFirst();
    }

    /** The hint for one line, empty if that line was cuttable and so got advice in dollars instead. */
    public Optional<SavingHint> hintFor(String lineItemId) {
        return hints.stream().filter(hint -> hint.lineItemId().equals(lineItemId)).findFirst();
    }
}
