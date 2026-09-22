package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * Answers "can I afford this today", over any spending category.
 *
 * <pre>
 * remainingBudget  = allowance - spentMonthToDate
 * remainingDays    = daysInMonth - dayOfMonth + 1          (today counts)
 * sustainableDaily = remainingBudget / remainingDays
 *
 * verdict          = COMFORTABLE  if price &lt;= sustainableDaily
 *                    SUSTAINABLE  if price is within a tolerance above it
 *                    OVER_BUDGET  otherwise
 * </pre>
 *
 * <p>It is the allocation engine at a shorter horizon and needs no model to decide anything. The
 * allocator asks whether $3,000 is reachable by March; this asks whether $25 is affordable today.
 * Both take a budget, subtract what is committed, spread the remainder over the periods left, and
 * compare. Nothing here could be improved by asking something to guess.
 *
 * <p>Pure arithmetic, deliberately not a Spring service, and the evaluation date arrives on the
 * request rather than being read - the same two properties that make the allocator exhaustively
 * testable in milliseconds, and both enforced by ArchUnit.
 */
public final class SpendDecision {

    /**
     * How far above the daily rate still counts as sustainable. A tenth, so a meal a few cents over
     * the rate is not reported as unaffordable: an engine that raises an alarm at twenty cents
     * teaches the user to ignore alarms, which costs more than the twenty cents.
     *
     * <p>A whole percentage rather than a decimal, because a raw {@code double} in the domain is
     * what {@code Money} exists to prevent.
     */
    public static final int SUSTAINABLE_TOLERANCE_PERCENT = 10;

    private SpendDecision() {}

    public static SpendAssessment decide(SpendDecisionRequest request) {
        Objects.requireNonNull(request, "request");

        Money remaining = request.remainingBudget();
        int days = request.remainingDays();
        Money sustainableDaily = sustainableDaily(remaining, days);

        Money price = request.purchase().price();
        return new SpendAssessment(
                request.category(),
                request.purchase(),
                request.allowance(),
                request.spentMonthToDate(),
                remaining,
                days,
                sustainableDaily,
                verdictFor(price, sustainableDaily),
                catchUpFor(price, remaining, sustainableDaily, days),
                cheaperThan(request, sustainableDaily));
    }

    /**
     * What is left, per day left. Floored at zero rather than reported negative: a user who is
     * already over their allowance has nothing per day, and a negative daily rate is not a figure
     * anyone can act on. The unclamped remainder still travels on the assessment, so the overspend
     * is reported rather than hidden.
     */
    private static Money sustainableDaily(Money remainingBudget, int remainingDays) {
        return Amounts.perPeriodAtMost(remainingBudget.max(Money.ZERO), remainingDays);
    }

    private static SpendVerdict verdictFor(Money price, Money sustainableDaily) {
        if (price.compareTo(sustainableDaily) <= 0) {
            return SpendVerdict.COMFORTABLE;
        }
        Money tolerated = Amounts.percentOf(sustainableDaily, 100 + SUSTAINABLE_TOLERANCE_PERCENT);
        return price.compareTo(tolerated) <= 0 ? SpendVerdict.SUSTAINABLE : SpendVerdict.OVER_BUDGET;
    }

    /**
     * How the rest of the month absorbs a purchase above the daily rate.
     *
     * <p>Two cases, and the second is the one worth being careful about. Where enough is left, the
     * following days simply run at what remains after the purchase, rounded down so they cannot
     * together exceed it. Where nothing like enough is left - the allowance was already blown, or
     * this is the last day of the month and there are no following days at all - there is no plan
     * that works, and the answer says how much runs past the end of the month instead of inventing
     * one.
     */
    private static CatchUpPlan catchUpFor(
            Money price, Money remainingBudget, Money sustainableDaily, int remainingDays) {

        if (price.compareTo(sustainableDaily) <= 0) {
            return null;
        }
        int followingDays = remainingDays - 1;
        Money leftAfterPurchase = remainingBudget.minus(price);

        if (followingDays < 1 || leftAfterPurchase.isNegative()) {
            // The most the month can give is spending nothing at all for whatever days remain, so
            // that is what is reported, together with what it still fails to cover.
            return new CatchUpPlan(
                    Money.ZERO,
                    followingDays >= 1 ? sustainableDaily : Money.ZERO,
                    Math.max(followingDays, 0),
                    false,
                    price.minus(remainingBudget).max(Money.ZERO));
        }

        Money reducedDailyRate = Amounts.perPeriodAtMost(leftAfterPurchase, followingDays);
        return new CatchUpPlan(
                reducedDailyRate,
                sustainableDaily.minus(reducedDailyRate),
                followingDays,
                true,
                Money.ZERO);
    }

    /**
     * The next rung down, already judged. A warning on its own is a scold; this is what turns it into
     * advice. Empty only when the user picked the cheapest thing on the ladder, or brought no ladder,
     * because suggesting something that is not actually cheaper would be worse than saying nothing.
     */
    private static CheaperOption cheaperThan(SpendDecisionRequest request, Money sustainableDaily) {
        Optional<TicketEstimate> cheaper = request.ladder().nextCheaperThan(request.purchase().price());
        return cheaper
                .map(estimate -> new CheaperOption(estimate, verdictFor(estimate.price(), sustainableDaily)))
                .orElse(null);
    }
}
