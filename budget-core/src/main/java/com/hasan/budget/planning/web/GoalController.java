package com.hasan.budget.planning.web;

import com.hasan.budget.planning.application.GoalDraft;
import com.hasan.budget.planning.application.Keys;
import com.hasan.budget.planning.application.PlanService;
import com.hasan.budget.planning.application.PlanService.GoalAndPlan;
import com.hasan.budget.planning.application.PlanService.GoalChange;
import com.hasan.budget.planning.application.PlanView;
import com.hasan.budget.planning.application.PlanView.KeyLabel;
import com.hasan.budget.planning.application.Wording;
import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.shared.Money;
import com.hasan.budget.web.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Goals, and the recompute that follows every change to them.
 *
 * <p>Adding a goal returns the whole new plan rather than just the goal, because the answer the user
 * is actually asking for is what it costs them elsewhere. Each change appends a snapshot, so the
 * history can later say what this particular goal did to the rest of the plan.
 *
 * <p>There is no field here for money a goal already has. What it holds comes from the balance, and
 * from nowhere else, so the same dollars can never be counted towards it twice.
 */
@RestController
@RequestMapping("/api/v1/goals")
class GoalController {

    private final PlanService plans;

    GoalController(PlanService plans) {
        this.plans = plans;
    }

    record GoalRequest(String name, BigDecimal target, String deadline, String priority) {}

    record GoalView(
            String id, String name, KeyLabel priority, String target, LocalDate deadline, boolean finishFirst) {}

    /**
     * @param plan null when the plan cannot be made yet, in which case {@code waitingFor} says what
     *     the user still has to tell us. The goal is saved either way.
     */
    record GoalAndPlanView(GoalView goal, PlanView plan, String waitingFor) {}

    record PlanOnlyView(PlanView plan, String waitingFor) {}

    record FinishFirstRequest(String goalId) {}

    @GetMapping
    List<GoalView> goals(@CurrentUser String userId) {
        Optional<String> finishFirst = plans.finishFirst(userId);
        return plans.goals(userId).stream().map(goal -> view(goal, finishFirst)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GoalAndPlanView add(@CurrentUser String userId, @RequestBody GoalRequest request) {
        return view(plans.addGoal(userId, change(request)), plans.finishFirst(userId));
    }

    @PutMapping("/{id}")
    GoalAndPlanView update(@CurrentUser String userId, @PathVariable String id, @RequestBody GoalRequest request) {
        return view(plans.updateGoal(userId, id, change(request)), plans.finishFirst(userId));
    }

    @DeleteMapping("/{id}")
    PlanOnlyView remove(@CurrentUser String userId, @PathVariable String id) {
        return plans.removeGoal(userId, id)
                .map(plan -> new PlanOnlyView(plan, null))
                .orElseGet(() -> new PlanOnlyView(null, waitingFor(userId)));
    }

    /**
     * Nominates the one goal the balance goes to first, ahead of how important the goals are. Send a
     * null id to go back to order of importance.
     */
    @PutMapping("/finish-first")
    PlanOnlyView finishFirst(@CurrentUser String userId, @RequestBody FinishFirstRequest request) {
        Optional<String> goalId = Optional.ofNullable(request.goalId()).filter(id -> !id.isBlank());
        return plans.finishFirst(userId, goalId)
                .map(plan -> new PlanOnlyView(plan, null))
                .orElseGet(() -> new PlanOnlyView(null, waitingFor(userId)));
    }

    private String waitingFor(String userId) {
        try {
            plans.assemble(userId);
            return null;
        } catch (RuntimeException waiting) {
            return waiting.getMessage();
        }
    }

    private static GoalChange change(GoalRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Give the goal a name, such as \"Car\".");
        }
        if (request.target() == null) {
            throw new IllegalArgumentException("Say how much this goal needs in total.");
        }
        return new GoalChange(
                request.name(),
                new Money(request.target()),
                deadline(request.deadline()),
                Keys.parse(Priority.class, request.priority(), "level of importance"));
    }

    private static LocalDate deadline(String deadline) {
        if (deadline == null || deadline.isBlank()) {
            throw new IllegalArgumentException("Say when you want this goal reached, as a date like 2027-06-30.");
        }
        try {
            return LocalDate.parse(deadline.strip());
        } catch (DateTimeParseException malformed) {
            throw new IllegalArgumentException("\"" + deadline + "\" is not a date. Use the form 2027-06-30.");
        }
    }

    private static GoalAndPlanView view(GoalAndPlan result, Optional<String> finishFirst) {
        return new GoalAndPlanView(view(result.goal(), finishFirst), result.plan(), result.waitingFor());
    }

    private static GoalView view(GoalDraft goal, Optional<String> finishFirst) {
        return new GoalView(
                goal.id(),
                goal.name(),
                new KeyLabel(Keys.of(goal.priority()), Wording.priority(goal.priority()).label()),
                goal.target().toString(),
                goal.deadline(),
                finishFirst.filter(goal.id()::equals).isPresent());
    }
}
