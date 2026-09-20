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
 */
public record SurplusBreakdown(
        Money income,
        Money fixedTotal,
        Money cappedTotal,
        Money lineItemTotal,
        Money discretionaryFloor,
        Money alreadySaving,
        Money surplus,
        List<CategoryLine> lines) {

    public SurplusBreakdown {
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(surplus, "surplus");
        lines = List.copyOf(lines);
    }
}
