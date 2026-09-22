package com.hasan.budget.planning.application;

import com.hasan.budget.shared.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Hands the considered balance - a stock, not a rate - to goals before the allocator runs.
 *
 * <p>This is the only way the balance reaches a plan's goals, and it does so through a lever that
 * already existed: raising a goal's {@code saved}. Crediting $20,000 to a $20,000 car makes its
 * monthly requirement zero and frees the whole surplus for the next goal. The allocator never sees
 * the balance at all, because it takes one monthly {@code Money}; a stock divisible across goals would
 * be a second resource, and greedy allocation is not optimal for two.
 *
 * <p>Order is descending priority, with one exception the user chooses: a goal nominated to finish
 * first takes the balance ahead of everything else. Within a priority the sooner deadline goes first,
 * because it is the one a missing month hurts more, and the id breaks any remaining tie so the same
 * inputs always earmark the same way.
 *
 * <p>Pure, and deliberately so. Reversibility falls out of that: nothing is stored here, so lowering
 * the balance and planning again simply earmarks less, and the money goes back where it came from.
 */
public final class Earmarking {

    private Earmarking() {}

    /**
     * @param finishFirst the goal id the user nominated, if any. A nomination for a goal that no
     *     longer exists is ignored rather than rejected: deleting a goal must not break the next plan.
     */
    public static Earmarks earmark(Money balance, List<GoalDraft> goals, Optional<String> finishFirst) {
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(goals, "goals");
        Objects.requireNonNull(finishFirst, "finishFirst");

        Comparator<GoalDraft> order = Comparator
                .comparing((GoalDraft goal) -> !finishFirst.map(goal.id()::equals).orElse(false))
                .thenComparing(goal -> goal.priority().weight(), Comparator.reverseOrder())
                .thenComparing(GoalDraft::deadline)
                .thenComparing(GoalDraft::id);

        Money left = balance.max(Money.ZERO);
        Map<String, Money> byGoal = new LinkedHashMap<>();
        for (GoalDraft goal : goals.stream().sorted(order).toList()) {
            // Never more than the target: a goal cannot hold money it does not need, and whatever it
            // would have held stays available to the next goal and to the runway.
            Money given = left.min(goal.target());
            byGoal.put(goal.id(), given);
            left = left.minus(given);
        }
        return new Earmarks(byGoal, left);
    }

    /**
     * @param byGoal what each goal was credited, in the order the balance was handed out
     * @param unassigned what no goal needed. This, and only this, drives the runway figure, so the
     *     same dollar is never both earmarked for a goal and counted as months of cover.
     */
    public record Earmarks(Map<String, Money> byGoal, Money unassigned) {

        public Earmarks {
            byGoal = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byGoal));
            Objects.requireNonNull(unassigned, "unassigned");
        }

        public Money forGoal(String goalId) {
            return byGoal.getOrDefault(goalId, Money.ZERO);
        }

        public Money total() {
            return byGoal.values().stream().reduce(Money.ZERO, Money::plus);
        }

        public List<String> order() {
            return new ArrayList<>(byGoal.keySet());
        }
    }
}
