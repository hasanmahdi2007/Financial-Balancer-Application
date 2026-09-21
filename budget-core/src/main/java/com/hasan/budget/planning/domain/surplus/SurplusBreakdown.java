package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.Money;
import java.util.List;
import java.util.Objects;

/**
 * The result of the surplus calculation, with enough detail to explain itself.
 *
 * <p>Conservation must hold exactly:
 * {@code income == fixedTotal + cappedTotal + lineItemTotal + discretionaryFloor + surplus}.
 * No cent is created or lost. That property is the one most likely to catch a refactor, and it is
 * worth a test of its own.
 *
 * @param surplus may be negative, and deliberately is not clamped: essentials really can exceed
 *     income, and hiding that would make the tradeoff engine understate the cuts needed
 * @param alreadySaving displayed, never subtracted
 * @param assumedReduction the spending this surplus has already assumed away - what was spent above
 *     a local baseline, plus whatever discretionary spending exceeds the floor. Reported, never
 *     subtracted, and it does not enter conservation.
 *     <p>It exists because {@code surplus} is not money in the user's pocket. It is what they would
 *     have <em>if</em> they made this reduction. Presenting the surplus without it invites the
 *     reader to add a suggested cut on top and conclude a goal is reachable, when the real
 *     instruction was this reduction plus that cut. Anything showing a plan to a person must show
 *     both numbers.
 */
public record SurplusBreakdown(
        Money income,
        Money fixedTotal,
        Money cappedTotal,
        Money lineItemTotal,
        Money discretionaryFloor,
        Money alreadySaving,
        Money surplus,
        Money assumedReduction,
        List<CategoryLine> lines) {

    public SurplusBreakdown {
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(surplus, "surplus");
        Objects.requireNonNull(assumedReduction, "assumedReduction");
        lines = List.copyOf(lines);
    }

    /**
     * Everything the user must actually change for this plan to hold: the reduction the surplus
     * already took for granted, plus whatever further cuts the goals need on top of it. The second
     * figure alone is the one that misleads.
     */
    public Money totalReductionNeeded(Money furtherCuts) {
        Objects.requireNonNull(furtherCuts, "furtherCuts");
        return assumedReduction.plus(furtherCuts);
    }
}
