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
 * @param measuredSpending what a connected bank says a finished month actually cost. Read only for
 *     categories the user did not state; a category neither of them covers is assumed to run at its
 *     local figure, and the plan says so on that line.
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
        MeasuredSpending measuredSpending,
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
        Objects.requireNonNull(measuredSpending, "measuredSpending");
        Objects.requireNonNull(alreadySaving, "alreadySaving");
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
}
