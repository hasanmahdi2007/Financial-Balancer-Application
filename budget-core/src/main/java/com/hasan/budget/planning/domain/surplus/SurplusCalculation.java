package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        Map<SpendCategory, Money> observed = observedByCategory(input.observations());
        for (CategoryObservation observation : input.observations()) {
            lines.add(lineFor(observation));
        }

        Map<SpendCategory, Money> namedWithin = new EnumMap<>(SpendCategory.class);
        Set<String> itemIds = new HashSet<>();
        for (UserLineItem item : input.lineItems()) {
            // The id is the handle a proposed cut uses to name one thing rather than a category, so
            // two items sharing one would make "cut your gym by $20" ambiguous about which line it
            // meant - which defeats the only reason these items are named at all.
            if (!itemIds.add(item.id())) {
                throw new IllegalArgumentException(
                        "line item ids must be unique, but '" + item.id() + "' is used more than once");
            }
            if (!item.scope().isSubtractedInItsOwnRight()) {
                checkFitsInsideItsCategory(item, observed, namedWithin);
            }
            lines.add(lineFor(item));
        }

        Money fixedTotal = totalOf(lines, wholeCategoryUnder(BaselinePolicy.TAKE_AS_IS));
        Money cappedTotal = totalOf(lines, wholeCategoryUnder(BaselinePolicy.CAP_AT_BASELINE));
        Money lineItemTotal = totalOf(lines, line -> line.lineItemId() != null);

        // Discretionary lines are absent from all three totals by construction: their counted
        // amount is zero, because the floor below covers them in one figure rather than category by
        // category. Lines the user only put a name to are zero for the same reason - their money is
        // already inside their category's total. Conservation therefore still holds exactly.
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
                assumedReduction(input, fixedTotal, cappedTotal, lineItemTotal),
                lines);
    }

    /**
     * The difference between what the user actually spent and what this surplus charged them for.
     * It is everything the formula has already assumed away: spending above a local baseline, and
     * discretionary spending above the floor.
     *
     * <p>Without this figure the surplus reads as money in hand, and a cut suggested on top of it
     * reads as the whole instruction. It is not - the user has to make this reduction as well, and
     * following only the suggested cut leaves them exactly this much short.
     *
     * <p>Floored at zero: a user who spends less than their floor has assumed away nothing, and a
     * negative reduction is not a thing anyone can act on.
     */
    private static Money assumedReduction(
            SurplusInput input, Money fixedTotal, Money cappedTotal, Money lineItemTotal) {
        Money observedSpending = Money.ZERO;
        for (CategoryObservation observation : input.observations()) {
            observedSpending = observedSpending.plus(observation.actual());
        }
        for (UserLineItem item : input.lineItems()) {
            // Items that only name part of a category are already inside that category's observed
            // figure, so adding them here would count the same money twice.
            if (item.scope().isSubtractedInItsOwnRight()) {
                observedSpending = observedSpending.plus(item.monthlyAmount());
            }
        }
        return observedSpending
                .minus(fixedTotal)
                .minus(cappedTotal)
                .minus(lineItemTotal)
                .minus(input.discretionaryFloor())
                .max(Money.ZERO);
    }

    private static Map<SpendCategory, Money> observedByCategory(List<CategoryObservation> observations) {
        Map<SpendCategory, Money> observed = new EnumMap<>(SpendCategory.class);
        for (CategoryObservation observation : observations) {
            // Capping each half of a split category separately would allow the baseline twice and
            // quietly understate the spend, so this is rejected rather than summed here: whoever
            // produced two observations is the only one who knows if they overlap.
            if (observed.putIfAbsent(observation.category(), observation.actual()) != null) {
                throw new IllegalArgumentException(
                        "at most one observation per category, but " + observation.category()
                                + " appears more than once");
            }
        }
        return observed;
    }

    /**
     * An item the user only put a name to has to fit inside the category it names part of. Naming
     * $90 of gym inside $60 of observed subscriptions is a data error, and letting it through would
     * leave a cut proposed against money that is not there.
     */
    private static void checkFitsInsideItsCategory(
            UserLineItem item, Map<SpendCategory, Money> observed, Map<SpendCategory, Money> namedWithin) {
        Money categoryTotal = observed.get(item.parent());
        if (categoryTotal == null) {
            throw new IllegalArgumentException(
                    "'" + item.label() + "' is named as part of " + item.parent()
                            + ", but nothing was observed in that category for it to be part of");
        }
        Money namedSoFar = namedWithin.merge(item.parent(), item.monthlyAmount(), Money::plus);
        if (namedSoFar.compareTo(categoryTotal) > 0) {
            throw new IllegalArgumentException(
                    "items named inside " + item.parent() + " total " + namedSoFar
                            + ", which is more than the " + categoryTotal + " observed there");
        }
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
                category.baselinePolicy(),
                // A whole category has no personal answer attached, so it takes the category's own
                // default. Only a named item can carry the user's override.
                category.defaultRigidity());
    }

    private static CategoryLine lineFor(UserLineItem item) {
        BaselinePolicy policy = item.parent().baselinePolicy();
        // Whether the amount reaches the arithmetic is data on ItemScope rather than a second switch
        // here. An item that only names part of a category counts zero - its money is already in
        // that category's total - but it still appears as a line, because naming it is the entire
        // reason it exists: "cut your gym by $20" is actionable where "cut Subscriptions" is not.
        //
        // A named item carries no baseline of its own, and the parent's baseline describes the whole
        // category rather than this one commitment, so there is nothing here to cap against. That is
        // not a gap: the user stating "my season ticket is $300" is already the authoritative
        // figure, which is exactly why a user figure outranks a local baseline everywhere else too.
        Money counted = item.scope().isSubtractedInItsOwnRight()
                ? countedAmount(policy, item.monthlyAmount(), null)
                : Money.ZERO;

        return new CategoryLine(
                item.parent(),
                item.id(),
                item.label(),
                item.monthlyAmount(),
                null,
                counted,
                policy,
                // The user's own answer, carried onto the line so whoever proposes cuts reads it
                // here rather than having to go back to the input and remember to.
                item.rigidity());
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
