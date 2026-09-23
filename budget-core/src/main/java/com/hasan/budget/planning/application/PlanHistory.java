package com.hasan.budget.planning.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What changed between one plan and the one before it.
 *
 * <p>Worked out by comparing two stored snapshots, never by recomputing either. That is the whole
 * reason snapshots are append-only: "adding the emergency fund pushed the car from on track to
 * behind" is only a true sentence if both halves are what the user was actually shown at the time.
 */
public final class PlanHistory {

    private PlanHistory() {}

    /** @param changes null for the first plan, which had nothing before it */
    public record Entry(String id, Instant takenAt, String reason, Changes changes) {}

    public record Changes(
            List<String> goalsAdded, List<String> goalsRemoved, Shift surplus, List<GoalChange> goals) {

        public Changes {
            goalsAdded = List.copyOf(goalsAdded);
            goalsRemoved = List.copyOf(goalsRemoved);
            goals = List.copyOf(goals);
        }
    }

    public record Shift(String before, String after) {}

    public record GoalChange(String id, String name, GoalState before, GoalState after) {}

    public record GoalState(String status, String monthlyFunded, String fromBalance) {

        static GoalState of(PlanView.Goal goal) {
            return new GoalState(goal.status().label(), goal.monthlyFunded(), goal.fromBalance());
        }
    }

    /** @param newestFirst every snapshot for one user, newest first, as the store returns them */
    public static List<Entry> of(List<PlanView> newestFirst) {
        Objects.requireNonNull(newestFirst, "newestFirst");
        List<Entry> entries = new ArrayList<>(newestFirst.size());
        for (int i = 0; i < newestFirst.size(); i++) {
            PlanView plan = newestFirst.get(i);
            PlanView previous = i + 1 < newestFirst.size() ? newestFirst.get(i + 1) : null;
            entries.add(new Entry(
                    plan.id(), plan.takenAt(), plan.reason(), previous == null ? null : between(previous, plan)));
        }
        return entries;
    }

    static Changes between(PlanView before, PlanView after) {
        Map<String, PlanView.Goal> earlier = byId(before.goals());
        Map<String, PlanView.Goal> later = byId(after.goals());

        List<String> added = after.goals().stream()
                .filter(goal -> !earlier.containsKey(goal.id()))
                .map(PlanView.Goal::name)
                .toList();
        List<String> removed = before.goals().stream()
                .filter(goal -> !later.containsKey(goal.id()))
                .map(PlanView.Goal::name)
                .toList();
        List<GoalChange> moved = new ArrayList<>();
        for (PlanView.Goal goal : after.goals()) {
            PlanView.Goal was = earlier.get(goal.id());
            if (was != null && !GoalState.of(was).equals(GoalState.of(goal))) {
                moved.add(new GoalChange(goal.id(), goal.name(), GoalState.of(was), GoalState.of(goal)));
            }
        }
        return new Changes(
                added, removed, new Shift(before.surplus().amount(), after.surplus().amount()), moved);
    }

    private static Map<String, PlanView.Goal> byId(List<PlanView.Goal> goals) {
        return goals.stream().collect(Collectors.toMap(PlanView.Goal::id, Function.identity()));
    }
}
