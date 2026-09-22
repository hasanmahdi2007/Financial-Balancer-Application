package com.hasan.budget.costofliving.web;

import com.hasan.budget.costofliving.application.UserFigureService;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.UserOverride;
import com.hasan.budget.costofliving.port.UserOverrideStore;
import com.hasan.budget.planning.application.Keys;
import com.hasan.budget.planning.application.PlanView.KeyLabel;
import com.hasan.budget.planning.application.PlanView.LabelMeaning;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.web.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's own figures: what they say things actually cost them.
 *
 * <p>These are the answers to the manual form, and corrections to anything we researched. A figure
 * saved here applies to that person's plan immediately, beats every other source permanently, and is
 * visible to nobody else - if we estimate $400 of groceries and the user knows theirs is $250, $250
 * wins, including over a government statistic, because they are the only source with their receipts.
 *
 * <p>Sharing a figure with other people is a separate, later, opt-in step. Nothing here leaves the
 * account on its own.
 */
@RestController
@RequestMapping("/api/v1/overrides")
class OverrideController {

    private final UserFigureService figures;
    private final UserOverrideStore stored;

    OverrideController(UserFigureService figures, UserOverrideStore stored) {
        this.figures = figures;
        this.stored = stored;
    }

    record OverrideRequest(BigDecimal amount) {}

    record OverrideView(KeyLabel category, String amount, LocalDate setOn, LabelMeaning basis) {}

    @GetMapping
    List<OverrideView> overrides(@CurrentUser String userId) {
        return stored.findAll(userId).values().stream().map(OverrideController::view).toList();
    }

    @PutMapping("/{category}")
    OverrideView save(
            @CurrentUser String userId, @PathVariable String category, @RequestBody OverrideRequest request) {
        SpendCategory spendCategory = Keys.parse(SpendCategory.class, category, "kind of spending");
        if (request.amount() == null) {
            throw new IllegalArgumentException(
                    "Say what you spend on " + spendCategory.label().toLowerCase(java.util.Locale.ENGLISH)
                            + " each month.");
        }
        Money amount = new Money(request.amount());
        if (amount.isNegative()) {
            throw new IllegalArgumentException("An amount cannot be below zero.");
        }
        // No bank evidence to check it against yet, so the figure is recorded as the user stated it.
        // Corroboration is recorded rather than enforced in any case: their bank disagreeing does not
        // make them wrong, and rent paid in cash leaves no trace.
        return view(figures.record(userId, spendCategory, amount, Optional.empty()));
    }

    private static OverrideView view(UserOverride override) {
        return new OverrideView(
                new KeyLabel(Keys.of(override.category()), override.category().label()),
                override.amount().toString(),
                override.setAt(),
                new LabelMeaning(Confidence.USER_PROVIDED.label(), Confidence.USER_PROVIDED.meaning()));
    }
}
