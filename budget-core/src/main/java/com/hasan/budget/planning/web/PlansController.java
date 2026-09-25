package com.hasan.budget.planning.web;

import com.hasan.budget.planning.application.PlanService;
import com.hasan.budget.planning.application.PlanService.NewPlan;
import com.hasan.budget.planning.application.PlanService.PlanChoice;
import com.hasan.budget.planning.application.PlanService.PlanChoices;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.web.CurrentUser;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A user's plans, one per place, and which one is in use.
 *
 * <p>Everything else under {@code /api/v1} acts on the plan in use, so switching here is all a move
 * takes: the dashboard, goals, spending and history all follow without changing shape.
 */
@RestController
@RequestMapping("/api/v1/plans")
class PlansController {

    private final PlanService plans;

    PlansController(PlanService plans) {
        this.plans = plans;
    }

    /**
     * @param city the id of a listed city, or null when the user's city is not listed
     * @param cityNotListed what they call their city in that case. Display only.
     * @param bringGoals whether the goals of the plan in use now come along. Left out means yes: a car
     *     someone is saving for does not stop mattering because they moved.
     */
    record NewPlanRequest(String country, String city, String cityNotListed, Boolean bringGoals) {}

    record UsePlanRequest(String planId) {}

    /** Every plan, or only those in one country: the list a user chooses from after moving there. */
    @GetMapping
    PlanChoices plans(@CurrentUser String userId, @RequestParam(required = false) String country) {
        return plans.plans(userId, Optional.ofNullable(country).filter(code -> !code.isBlank()).map(CountryCode::new));
    }

    @PostMapping
    ResponseEntity<PlanChoice> startPlan(@CurrentUser String userId, @RequestBody NewPlanRequest request) {
        if (request.country() == null || request.country().isBlank()) {
            throw new IllegalArgumentException("Choose the country this plan is for.");
        }
        PlanChoice plan = plans.startPlan(userId, new NewPlan(
                new CountryCode(request.country()),
                request.city() == null || request.city().isBlank() ? null : new MetroId(request.city().strip()),
                request.cityNotListed(),
                request.bringGoals() == null || request.bringGoals()));
        return ResponseEntity.created(URI.create("/api/v1/plans")).body(plan);
    }

    @PutMapping("/active")
    PlanChoice usePlan(@CurrentUser String userId, @RequestBody UsePlanRequest request) {
        if (request.planId() == null || request.planId().isBlank()) {
            throw new IllegalArgumentException("Choose which plan to use.");
        }
        return plans.usePlan(userId, request.planId());
    }
}
