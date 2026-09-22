package com.hasan.budget.planning.web;

import com.hasan.budget.planning.application.DecisionService;
import com.hasan.budget.planning.application.DecisionService.AffordQuestion;
import com.hasan.budget.planning.application.DecisionService.Affordability;
import com.hasan.budget.planning.application.DecisionService.RebalanceQuestion;
import com.hasan.budget.planning.application.Keys;
import com.hasan.budget.planning.application.PlanView.KeyLabel;
import com.hasan.budget.planning.application.PlanView.LabelMeaning;
import com.hasan.budget.planning.application.Wording;
import com.hasan.budget.planning.domain.decision.Adjustment;
import com.hasan.budget.planning.domain.decision.CatchUpPlan;
import com.hasan.budget.planning.domain.decision.RebalanceResult;
import com.hasan.budget.planning.domain.decision.SavingHint;
import com.hasan.budget.planning.domain.decision.SpendAssessment;
import com.hasan.budget.planning.domain.decision.SpendBand;
import com.hasan.budget.planning.domain.decision.SpendVerdict;
import com.hasan.budget.planning.domain.decision.TicketEstimate;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.web.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two questions that are answered in one tap: "can I afford this today?" and "give me more for
 * this, and take it from the rest".
 *
 * <p>Two rules shape these responses rather than decorate them. <strong>A warning never travels
 * alone</strong>: every verdict carries the next cheaper option and that option's own verdict, because
 * a warning by itself is a scold and the point is to say what would work. And <strong>a line the plan
 * may not cut gets a sentence, never a number</strong>: hints are qualitative on purpose, so they
 * cannot be added into a total and promise money nobody has found.
 */
@RestController
@RequestMapping("/api/v1/decisions")
class DecisionController {

    private final DecisionService decisions;

    DecisionController(DecisionService decisions) {
        this.decisions = decisions;
    }

    /**
     * @param band a kind of meal, for eating out; leave it out and give a price instead for anything else
     * @param spentThisMonth what has already gone on this category this month. Required: assuming zero
     *     would make every answer look more affordable than it is.
     */
    record AffordRequest(
            String category, String band, String label, BigDecimal price, BigDecimal spentThisMonth) {}

    record PurchaseView(String label, String covers, String price, LabelMeaning basis) {}

    record CatchUpView(
            String perDayAfter, String lessPerDay, int days, boolean fitsThisMonth, String runsIntoNextMonth) {}

    record CheaperView(String label, String covers, String price, LabelMeaning basis, LabelMeaning verdict) {}

    record AffordView(
            LabelMeaning verdict,
            KeyLabel category,
            PurchaseView purchase,
            String allowance,
            String allowanceBasis,
            String spentThisMonth,
            String left,
            int daysLeft,
            String perDay,
            CatchUpView catchUp,
            CheaperView cheaper) {}

    /** @param percentOfBalance a whole percentage of the money in scope; give this or {@code amount} */
    record RebalanceRequestBody(String raise, BigDecimal amount, Integer percentOfBalance) {}

    record ChangeView(String id, String label, String from, String to, String by) {}

    record HintView(String id, String label, String hint) {}

    record OptionView(String label, String meaning, String answerWith) {}

    record RebalanceView(
            LabelMeaning outcome,
            String granted,
            String stillShort,
            List<ChangeView> changes,
            List<HintView> hints,
            List<OptionView> options) {}

    @PostMapping("/afford")
    AffordView afford(@CurrentUser String userId, @RequestBody AffordRequest request) {
        SpendCategory category = Keys.parse(SpendCategory.class, request.category(), "kind of spending");
        Affordability answer = decisions.afford(userId, new AffordQuestion(
                category,
                request.band() == null ? null : Keys.parse(SpendBand.class, request.band(), "kind of meal"),
                request.label(),
                request.price() == null ? null : new Money(request.price()),
                request.spentThisMonth() == null ? null : new Money(request.spentThisMonth())));
        return view(answer);
    }

    @PostMapping("/rebalance")
    RebalanceView rebalance(@CurrentUser String userId, @RequestBody RebalanceRequestBody request) {
        RebalanceResult result = decisions.rebalance(userId, new RebalanceQuestion(
                request.raise(),
                request.amount() == null ? null : new Money(request.amount()),
                request.percentOfBalance()));
        return view(result);
    }

    private static AffordView view(Affordability answer) {
        SpendAssessment assessment = answer.assessment();
        return new AffordView(
                new LabelMeaning(assessment.verdict().label(), assessment.verdict().meaning()),
                new KeyLabel(Keys.of(assessment.category()), assessment.category().label()),
                view(assessment.purchase()),
                assessment.allowance().toString(),
                answer.allowanceBasis(),
                assessment.spentMonthToDate().toString(),
                assessment.remainingBudget().toString(),
                assessment.remainingDays(),
                assessment.sustainableDaily().toString(),
                assessment.catchUp().map(DecisionController::view).orElse(null),
                assessment.cheaper()
                        .map(cheaper -> view(cheaper.estimate(), cheaper.verdict()))
                        .orElse(null));
    }

    private static PurchaseView view(TicketEstimate estimate) {
        return new PurchaseView(
                estimate.label(),
                estimate.covers(),
                estimate.price().toString(),
                new LabelMeaning(estimate.basis().label(), estimate.basis().meaning()));
    }

    private static CheaperView view(TicketEstimate estimate, SpendVerdict verdict) {
        return new CheaperView(
                estimate.label(),
                estimate.covers(),
                estimate.price().toString(),
                new LabelMeaning(estimate.basis().label(), estimate.basis().meaning()),
                new LabelMeaning(verdict.label(), verdict.meaning()));
    }

    private static CatchUpView view(CatchUpPlan plan) {
        return new CatchUpView(
                plan.reducedDailyRate().toString(),
                plan.reductionPerDay().toString(),
                plan.days(),
                plan.fitsThisMonth(),
                plan.spillsIntoNextMonth().toString());
    }

    private static RebalanceView view(RebalanceResult result) {
        return new RebalanceView(
                new LabelMeaning(result.outcome().label(), result.outcome().meaning()),
                result.granted().toString(),
                result.residualGap().toString(),
                result.adjustments().stream().map(DecisionController::view).toList(),
                result.hints().stream().map(DecisionController::view).toList(),
                result.options().stream()
                        .map(option -> new OptionView(option.label(), option.meaning(), Wording.answerWith(option)))
                        .toList());
    }

    private static ChangeView view(Adjustment adjustment) {
        return new ChangeView(
                adjustment.lineItemId(),
                adjustment.label(),
                adjustment.from().toString(),
                adjustment.to().toString(),
                adjustment.change().toString());
    }

    private static HintView view(SavingHint hint) {
        return new HintView(hint.lineItemId(), hint.label(), hint.lever());
    }
}
