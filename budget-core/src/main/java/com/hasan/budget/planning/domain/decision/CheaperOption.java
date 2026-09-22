package com.hasan.budget.planning.domain.decision;

import java.util.Objects;

/**
 * The next rung down the ladder, with its own verdict already worked out.
 *
 * <p>The verdict travels with it deliberately. Offering a cheaper option without saying whether that
 * one is affordable either leaves the user to do the arithmetic again or, worse, implies it is fine
 * when the allowance is blown and nothing on the ladder is.
 */
public record CheaperOption(TicketEstimate estimate, SpendVerdict verdict) {

    public CheaperOption {
        Objects.requireNonNull(estimate, "estimate");
        Objects.requireNonNull(verdict, "verdict");
    }
}
