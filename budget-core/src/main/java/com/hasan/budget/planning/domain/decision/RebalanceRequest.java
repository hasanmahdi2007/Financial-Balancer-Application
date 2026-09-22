package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * "Give me this much more for that, and take it from the rest."
 *
 * @param lines the whole plan as it stands. All of it, not only the candidates, because the lines that
 *     may not give are exactly the ones that need a hint, and a caller who filtered them out first
 *     would produce advice that silently ignored the user's largest outflows.
 * @param targetId which line to raise, by the id it carries
 * @param increase how much more it should have. The user may have typed dollars or a percentage; both
 *     arrive here as the one figure {@link Share} converted them to.
 */
public record RebalanceRequest(List<BudgetLine> lines, String targetId, Money increase) {

    public RebalanceRequest {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(increase, "increase");
        lines = List.copyOf(lines);
        if (!increase.isPositive()) {
            throw new IllegalArgumentException("increase must be positive but was " + increase);
        }
        // An id is how an adjustment names one thing rather than a category, so a duplicate would make
        // "your gym goes to $40" ambiguous about which line moved - which defeats naming them at all.
        Set<String> seen = new HashSet<>();
        for (BudgetLine line : lines) {
            if (!seen.add(line.id())) {
                throw new IllegalArgumentException(
                        "line ids must be unique, but '" + line.id() + "' is used more than once");
            }
        }
        if (lines.stream().noneMatch(line -> line.id().equals(targetId))) {
            throw new IllegalArgumentException("no line has id '" + targetId + "' to raise");
        }
    }

    /** A request built from a share the user expressed as a percentage of some pool. */
    public static RebalanceRequest raise(List<BudgetLine> lines, String targetId, Share increase) {
        Objects.requireNonNull(increase, "increase");
        return new RebalanceRequest(lines, targetId, increase.amount());
    }

    /** The line being raised. Present by construction; the constructor rejects a target that is not here. */
    public BudgetLine target() {
        return lines.stream()
                .filter(line -> line.id().equals(targetId))
                .findFirst()
                .orElseThrow();
    }

    /** The lines that are not the target, which is where the increase has to come from. */
    public List<BudgetLine> others() {
        return lines.stream().filter(line -> !line.id().equals(targetId)).toList();
    }

    /** The plan's total as it stands, which rebalancing must leave exactly where it found it. */
    public Money total() {
        return lines.stream().map(BudgetLine::amount).reduce(Money.ZERO, Money::plus);
    }
}
