package com.hasan.budget.planning.domain.surplus;

/**
 * Turns observed spending and localised baselines into a single surplus figure.
 *
 * <pre>
 * surplus = income
 *         - fixedCommitments                  (taken as-is, never capped)
 *         - sum of min(actual, baseline)       (CAP_AT_BASELINE categories only)
 *         - sum of user line items             (by their parent category's policy)
 *         - discretionaryFloor
 * </pre>
 *
 * <p>Pure arithmetic, deliberately not a Spring service. This is the piece most likely to be wrong -
 * it already was once - and keeping it free of a container is what lets it be tested exhaustively in
 * milliseconds. An ArchUnit rule enforces that, along with the ban on reading a clock.
 *
 * <p>Implementations must switch on {@code BaselinePolicy}, which has three arms, and never on
 * {@code SpendCategory}, which has twelve. Adding a category has to stay a one-row change.
 *
 * <p><strong>Contract frozen by P0. Implementation belongs to packet P1.</strong>
 */
public final class SurplusCalculation {

    private SurplusCalculation() {}

    public static SurplusBreakdown compute(SurplusInput input) {
        throw new UnsupportedOperationException("P1 implements this; see .claude/packets/P1.md");
    }
}
