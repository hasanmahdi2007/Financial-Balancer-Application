package com.hasan.budget.planning.web;

import com.hasan.budget.planning.application.NotFoundException;
import com.hasan.budget.planning.application.PlanHistory;
import com.hasan.budget.planning.application.PlanService;
import com.hasan.budget.planning.application.PlanView;
import com.hasan.budget.web.CurrentUser;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The plan, and every plan before it.
 *
 * <p>{@code POST} makes a new one and keeps it; {@code GET} returns the last one made, without
 * recomputing. The difference matters: a plan the user is looking at should not change under them
 * because a figure aged overnight, and the history would be meaningless if reading it recomputed.
 */
@RestController
@RequestMapping("/api/v1/plan")
class PlanController {

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    @PostMapping
    ResponseEntity<PlanView> makePlan(@CurrentUser String userId) {
        PlanView plan = plans.plan(userId);
        return ResponseEntity.created(URI.create("/api/v1/plan/history/" + plan.id())).body(plan);
    }

    @GetMapping
    PlanView latest(@CurrentUser String userId) {
        return plans.latest(userId)
                .orElseThrow(() -> new NotFoundException("You have no plan yet. Ask for one and we will make it."));
    }

    @GetMapping("/history")
    List<PlanHistory.Entry> history(@CurrentUser String userId) {
        return plans.history(userId);
    }

    @GetMapping("/history/{id}")
    PlanView snapshot(@CurrentUser String userId, @PathVariable String id) {
        return plans.snapshot(userId, id);
    }
}
