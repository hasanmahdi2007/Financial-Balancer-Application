package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.profile.domain.ConsideredFunds;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one plan is built from, gathered before any arithmetic runs.
 *
 * <p>Baselines arrive here still carrying their provenance, because the view needs it. It is
 * {@link PlanAssembler}'s job, and only its job, to strip that off before the math sees a number.
 *
 * @param funds the money the user agreed the app may plan with. Its income is a rate and feeds the
 *     surplus; its balance is a stock and reaches goals only through earmarking.
 * @param statedSpending what the user says they spend. It outranks every other source, a bank
 *     included: they may know last month was not a normal month, and nothing else can.
 * @param measuredMonth what a connected bank says a finished month actually cost. Read only for
 *     categories the user did not state; a category neither of them covers is assumed to run at its
 *     local figure, and the plan says so on that line.
 * @param alreadySaving what the user says they already put into savings each month, or null when they
 *     have not said - which is what lets a connected bank answer instead. See {@link #savingEachMonth()}.
 * @param taxReserve present only for income that arrives untaxed. Empty, not zero, for everyone else:
 *     a zero tax line invites somebody to "fix" it later.
 * @param lifestyle null when the user has not been asked, which the floor treats as a real state
 * @param leastForEnjoyingLife the user's own answer to the floor question, or null if not given
 * @param cityLabel what to call the user's city when explaining a suggestion; display only
 * @param finishFirst a goal the user nominated to take the balance ahead of priority order
 */
public record PlanningInputs(
        ConsideredFunds funds,
        Map<SpendCategory, ResolvedBaseline> baselines,
        Map<SpendCategory, Money> statedSpending,
        MeasuredMonth measuredMonth,
        List<UserLineItem> lineItems,
        Money alreadySaving,
        Optional<Money> taxReserve,
        LifestyleTier lifestyle,
        Money leastForEnjoyingLife,
        String cityLabel,
        List<GoalDraft> goals,
        Optional<String> finishFirst,
        LocalDate asOf) {

    public PlanningInputs {
        Objects.requireNonNull(funds, "funds");
        Objects.requireNonNull(measuredMonth, "measuredMonth");
        Objects.requireNonNull(taxReserve, "taxReserve");
        Objects.requireNonNull(finishFirst, "finishFirst");
        Objects.requireNonNull(asOf, "asOf");
        baselines = Map.copyOf(baselines);
        statedSpending = Map.copyOf(statedSpending);
        lineItems = List.copyOf(lineItems);
        goals = List.copyOf(goals);
        if (statedSpending.containsKey(SpendCategory.TAX_RESERVE)) {
            throw new IllegalArgumentException(
                    "tax set aside is worked out from the tax rate, not stated as spending");
        }
    }

    /**
     * What the user already puts into savings each month, from the best source there is: their own
     * answer, then what a connected bank recorded for the last complete month, then nothing claimed.
     * The same order as spending, and for the same reason - only the user knows whether last month
     * was a normal one.
     */
    public Money savingEachMonth() {
        if (alreadySaving != null) {
            return alreadySaving;
        }
        return measuredMonth.alreadySaving() != null ? measuredMonth.alreadySaving() : Money.ZERO;
    }

    /** True when {@link #savingEachMonth()} was read off the user's bank rather than told to us. */
    public boolean savingWasMeasured() {
        return alreadySaving == null && measuredMonth.alreadySaving() != null;
    }
}
