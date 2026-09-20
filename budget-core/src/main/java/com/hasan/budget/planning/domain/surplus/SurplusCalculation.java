package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

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
        Objects.requireNonNull(input, "input");

        List<CategoryLine> lines = new ArrayList<>(input.observations().size() + input.lineItems().size());
        EnumSet<SpendCategory> seen = EnumSet.noneOf(SpendCategory.class);
        for (CategoryObservation observation : input.observations()) {
            if (!seen.add(observation.category())) {
                // Capping each half of a split category separately would allow the baseline twice
                // and quietly understate the spend, so this is rejected rather than summed here:
                // whoever produced two observations is the only one who knows if they overlap.
                throw new IllegalArgumentException(
                        "at most one observation per category, but " + observation.category()
                                + " appears more than once");
            }
            lines.add(lineFor(observation));
        }
        for (UserLineItem item : input.lineItems()) {
            lines.add(lineFor(item));
        }

        Money fixedTotal = totalOf(lines, wholeCategoryUnder(BaselinePolicy.TAKE_AS_IS));
        Money cappedTotal = totalOf(lines, wholeCategoryUnder(BaselinePolicy.CAP_AT_BASELINE));
        Money lineItemTotal = totalOf(lines, line -> line.lineItemId() != null);

        // Discretionary lines are absent from all three totals by construction: their counted
        // amount is zero, because the floor below covers them in one figure rather than category by
        // category. Conservation therefore still holds exactly.
        Money surplus = input.income()
                .minus(fixedTotal)
                .minus(cappedTotal)
                .minus(lineItemTotal)
                .minus(input.discretionaryFloor());

        return new SurplusBreakdown(
                input.income(),
                fixedTotal,
                cappedTotal,
                lineItemTotal,
                input.discretionaryFloor(),
                input.alreadySaving(),
                surplus,
                lines);
    }

    private static CategoryLine lineFor(CategoryObservation observation) {
        SpendCategory category = observation.category();
        return new CategoryLine(
                category,
                null,
                category.label(),
                observation.actual(),
                observation.effectiveBaseline(),
                countedAmount(category.baselinePolicy(), observation.actual(), observation.effectiveBaseline()),
                category.baselinePolicy());
    }

    private static CategoryLine lineFor(UserLineItem item) {
        BaselinePolicy policy = item.parent().baselinePolicy();
        // A named item carries no baseline of its own, and the parent's baseline describes the
        // whole category rather than this one commitment, so there is nothing here to cap against.
        // That is not a gap: the user stating "my season ticket is $300" is already the authoritative
        // figure, which is exactly why a user figure outranks a local baseline everywhere else too.
        return new CategoryLine(
                item.parent(),
                item.id(),
                item.label(),
                item.monthlyAmount(),
                null,
                countedAmount(policy, item.monthlyAmount(), null),
                policy);
    }

    /**
     * The only switch in the calculation, and it is over {@link BaselinePolicy} - three arms -
     * rather than over {@link SpendCategory}, which has twelve. Adding a category is then one enum
     * row with nothing to change here, and a user's own line item needs no arm of its own because
     * its parent category already selects one.
     *
     * @param effectiveBaseline already resolved by the time it arrives, and null where none applies.
     *     Where the user has stated their own figure this is that figure; the calculation neither
     *     knows nor asks, because the moment the arithmetic can see provenance somebody writes
     *     {@code if (confidence == ESTIMATED)} in the middle of it.
     */
    private static Money countedAmount(BaselinePolicy policy, Money actual, Money effectiveBaseline) {
        return switch (policy) {
            // You cannot cap a lease mid-month. A cost-of-living figure says what things cost, not
            // what you should spend, so capping here would pretend the user can underpay and would
            // overstate the surplus by the difference.
            case TAKE_AS_IS -> actual;
            // Spending above the local expectation is controllable, which is what lets the plan say
            // "this is where your goal went". Below it, never inflate to the baseline.
            case CAP_AT_BASELINE -> effectiveBaseline == null ? actual : actual.min(effectiveBaseline);
            // Covered by the discretionary floor as a single figure, not category by category.
            case DISCRETIONARY -> Money.ZERO;
        };
    }

    private static Predicate<CategoryLine> wholeCategoryUnder(BaselinePolicy policy) {
        return line -> line.lineItemId() == null && line.policy() == policy;
    }

    private static Money totalOf(List<CategoryLine> lines, Predicate<CategoryLine> selector) {
        return lines.stream()
                .filter(selector)
                .map(CategoryLine::counted)
                .reduce(Money.ZERO, Money::plus);
    }
}
