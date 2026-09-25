package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.Staleness;
import com.hasan.budget.planning.application.AssembledPlan.Reduction;
import com.hasan.budget.planning.application.CutCandidates.SuggestedCut;
import com.hasan.budget.planning.application.PlanView.Basis;
import com.hasan.budget.planning.application.PlanView.Cut;
import com.hasan.budget.planning.application.PlanView.Cuts;
import com.hasan.budget.planning.application.PlanView.Goal;
import com.hasan.budget.planning.application.PlanView.Hint;
import com.hasan.budget.planning.application.PlanView.KeyLabel;
import com.hasan.budget.planning.application.PlanView.LabelMeaning;
import com.hasan.budget.planning.application.PlanView.LeftOver;
import com.hasan.budget.planning.application.PlanView.Line;
import com.hasan.budget.planning.application.PlanView.MoneyInScope;
import com.hasan.budget.planning.application.PlanView.Option;
import com.hasan.budget.planning.application.PlanView.Runway;
import com.hasan.budget.planning.application.PlanView.Surplus;
import com.hasan.budget.planning.application.PlanView.Today;
import com.hasan.budget.planning.domain.GoalAllocation;
import com.hasan.budget.planning.domain.decision.ResolutionOption;
import com.hasan.budget.planning.domain.surplus.CategoryLine;
import com.hasan.budget.planning.domain.surplus.SurplusBreakdown;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Words an assembled plan for a person.
 *
 * <p>This is where provenance finally surfaces. The assembler stripped it off before the arithmetic
 * ran; here it is put back beside each figure, so the user can always tell a figure they gave us from
 * one we researched from a country-wide guess.
 */
public final class PlanViews {

    /** The month a measured figure came from, as a person says it: "August 2026", never "2026-08". */
    private static final DateTimeFormatter MONTH_AND_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private PlanViews() {}

    public static PlanView from(AssembledPlan plan, String id, Instant takenAt, String reason, PlanView.Place place) {
        return new PlanView(
                id,
                takenAt,
                plan.inputs().asOf(),
                reason,
                today(plan),
                money(plan),
                surplus(plan),
                goals(plan),
                cuts(plan),
                plan.hints().stream().map(hint -> new Hint(hint.lineId(), hint.label(), hint.hint())).toList(),
                leftOver(plan),
                place);
    }

    /**
     * Where the user stands before the plan changes anything. Worded here, like every other sentence
     * on the plan, so the client never composes a claim about someone's money on its own.
     */
    private static Today today(AssembledPlan plan) {
        SurplusBreakdown breakdown = plan.breakdown();
        Money income = breakdown.income();
        Money left = breakdown.leftAsEntered();
        Money tax = breakdown.lines().stream()
                .filter(line -> line.lineItemId() == null && line.category() == SpendCategory.TAX_RESERVE)
                .map(CategoryLine::actual)
                .reduce(Money.ZERO, Money::plus);
        // Derived from the two figures above rather than summed again, so the card always adds up.
        Money spent = income.minus(tax).minus(left);

        String explanation = left.isNegative()
                ? "You spend $" + text(Money.ZERO.minus(left)) + " more than you earn each month. "
                        + "The changes below show where that gap could close."
                : "What is left each month from the figures you gave us, before any change we suggest. "
                        + "It is money you are either saving already or simply not spending.";

        String runway = null;
        if (left.isNegative()) {
            // The whole balance they gave us, not what the plan left unassigned: this card is from
            // before the plan hands any of it to a goal.
            var cover = com.hasan.budget.profile.domain.Runway.of(plan.inputs().funds(), Money.ZERO.minus(left));
            runway = cover.months() == 0
                    ? "You have nothing put by that would cover it."
                    : "What you have would cover it for about " + cover.months()
                            + (cover.months() == 1 ? " month." : " months.");
        }

        Money saving = breakdown.alreadySaving();
        String savingNote = saving.isPositive()
                ? "Of which you already move $" + text(saving) + " into savings."
                : saving.isNegative()
                        ? "You also took $" + text(Money.ZERO.minus(saving)) + " more out of savings than you put in."
                        : null;

        int estimated = plan.assumedSpending().size();
        String where = plan.inputs().cityLabel() == null ? "where you live" : plan.inputs().cityLabel();
        String estimatedNote = estimated == 0
                ? null
                : (estimated == 1 ? "One of these figures is" : estimated + " of these figures are")
                        + " our estimate for " + where + ", because you have not told us what you spend "
                        + (estimated == 1 ? "on it" : "on them") + ". Tell us and this becomes entirely yours.";

        return new Today(
                text(income),
                tax.isPositive() ? text(tax) : null,
                text(spent),
                text(left),
                explanation,
                runway,
                saving.isZero() ? null : text(saving),
                savingNote,
                estimated,
                estimatedNote,
                "Make every change above and you would have this much each month for your goals.");
    }

    private static MoneyInScope money(AssembledPlan plan) {
        var funds = plan.inputs().funds();
        var runway = plan.runway();
        Runway view = runway.indefinite()
                ? new Runway("Your money is not running down", null)
                : new Runway(
                        "What you have not put toward a goal would cover about " + runway.months()
                                + (runway.months() == 1 ? " month" : " months") + " of the shortfall",
                        runway.months());
        return new MoneyInScope(
                text(funds.monthlyIncome()),
                text(funds.consideredBalance()),
                text(funds.setAside()),
                text(plan.earmarks().total()),
                text(plan.earmarks().unassigned()),
                view);
    }

    private static Surplus surplus(AssembledPlan plan) {
        SurplusBreakdown breakdown = plan.breakdown();
        return new Surplus(
                text(breakdown.surplus()),
                breakdown.surplus().isNegative()
                        // Worded for where it now sits: after the changes that produce it, not above them.
                        ? "Your essential costs are more than your income, by this much each month, even "
                                + "after the changes above."
                        : "What would be left each month for your goals once you make the changes above. "
                                + "It is not money you have today.",
                text(breakdown.assumedReduction()),
                "This figure already counts on the changes above. Making only some of them would leave "
                        + "you short by the rest.",
                plan.reductions().stream().map(PlanViews::reduction).toList(),
                breakdown.lines().stream().map(line -> line(plan, line)).toList(),
                text(breakdown.discretionaryFloor()),
                plan.floor().basis(),
                text(breakdown.alreadySaving()),
                plan.inputs().savingWasMeasured()
                        ? "What went into your savings in "
                                + plan.inputs().measuredMonth().month().format(MONTH_AND_YEAR)
                                + ", from your bank, less anything you took back out. It is shown, never "
                                + "subtracted, because it is not spending."
                        : "What you already move into savings each month. It is shown, never subtracted, "
                                + "because it is not spending.");
    }

    private static PlanView.Reduction reduction(Reduction reduction) {
        return new PlanView.Reduction(
                reduction.label(), text(reduction.from()), text(reduction.to()), text(reduction.by()));
    }

    private static Line line(AssembledPlan plan, CategoryLine line) {
        boolean wholeCategory = line.lineItemId() == null;
        ResolvedBaseline source = wholeCategory ? plan.provenance().get(line.category()) : null;
        return new Line(
                PlanAssembler.lineId(line),
                line.label(),
                category(line.category()),
                text(line.actual()),
                line.baseline() == null ? null : text(line.baseline()),
                text(line.counted()),
                wholeCategory && plan.assumedSpending().contains(line.category()),
                wholeCategory && plan.measuredSpending().contains(line.category())
                        ? "what you spent in " + plan.inputs().measuredMonth().month().format(MONTH_AND_YEAR)
                        : null,
                howWilling(line.rigidity()),
                source == null ? null : basis(source));
    }

    private static Basis basis(ResolvedBaseline source) {
        LabelMeaning ageing = source.staleness() == Staleness.FRESH
                ? null
                : new LabelMeaning(
                        Wording.ageing(source.staleness()).label(), Wording.ageing(source.staleness()).meaning());
        return new Basis(source.confidence().label(), source.confidence().meaning(), source.asOf(), ageing);
    }

    private static List<Goal> goals(AssembledPlan plan) {
        Map<String, GoalAllocation> allocations = plan.allocation().goalAllocations().stream()
                .collect(Collectors.toMap(GoalAllocation::goalId, Function.identity()));
        String finishFirst = plan.inputs().finishFirst().orElse(null);
        return plan.inputs().goals().stream()
                .map(goal -> {
                    GoalAllocation allocation = allocations.get(goal.id());
                    Money fromBalance = plan.earmarks().forGoal(goal.id());
                    Wording.Words status = Wording.status(allocation.status());
                    return new Goal(
                            goal.id(),
                            goal.name(),
                            new KeyLabel(Keys.of(goal.priority()), Wording.priority(goal.priority()).label()),
                            text(goal.target()),
                            text(fromBalance),
                            text(goal.target().minus(fromBalance).max(Money.ZERO)),
                            goal.deadline(),
                            text(allocation.requiredMonthly()),
                            text(allocation.allocated()),
                            text(allocation.shortfall()),
                            goal.id().equals(finishFirst),
                            new LabelMeaning(status.label(), status.meaning()));
                })
                .toList();
    }

    private static Cuts cuts(AssembledPlan plan) {
        Money stillShort = plan.allocation().residualGap();
        List<Option> options = stillShort.isPositive()
                ? Arrays.stream(ResolutionOption.values())
                        .map(option -> new Option(option.label(), option.meaning(), Wording.answerWith(option)))
                        .toList()
                : List.of();
        return new Cuts(
                plan.suggestedCuts().stream().map(PlanViews::cut).toList(),
                text(plan.suggestedTotal()),
                text(plan.breakdown().assumedReduction()),
                text(plan.totalChange()),
                "Everything you would change to make this plan work: the changes the monthly figure "
                        + "already assumes, plus the cuts suggested on top.",
                text(stillShort),
                options);
    }

    private static Cut cut(SuggestedCut cut) {
        return new Cut(cut.label(), category(cut.category()), text(cut.amount()), howWilling(cut.rigidity()), cut.lineIds());
    }

    private static LeftOver leftOver(AssembledPlan plan) {
        Money unallocated = plan.allocation().unallocatedSurplus();
        return new LeftOver(
                text(unallocated),
                unallocated.isPositive()
                        ? "Left over each month once every goal is on track. You could save or invest it."
                        : "Nothing is left over once your goals are paid for.");
    }

    static KeyLabel category(SpendCategory category) {
        return new KeyLabel(Keys.of(category), category.label());
    }

    static KeyLabel howWilling(Rigidity rigidity) {
        return new KeyLabel(Keys.of(rigidity), Wording.howWilling(rigidity).label());
    }

    static String text(Money money) {
        return money.toString();
    }
}
