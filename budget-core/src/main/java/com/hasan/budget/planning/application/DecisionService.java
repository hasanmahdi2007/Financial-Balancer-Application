package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.planning.domain.decision.BudgetLine;
import com.hasan.budget.planning.domain.decision.ObservedTicket;
import com.hasan.budget.planning.domain.decision.PriceLadder;
import com.hasan.budget.planning.domain.decision.RebalanceRequest;
import com.hasan.budget.planning.domain.decision.RebalanceResult;
import com.hasan.budget.planning.domain.decision.Rebalancing;
import com.hasan.budget.planning.domain.decision.Share;
import com.hasan.budget.planning.domain.decision.SpendAssessment;
import com.hasan.budget.planning.domain.decision.SpendBand;
import com.hasan.budget.planning.domain.decision.SpendDecision;
import com.hasan.budget.planning.domain.decision.SpendDecisionRequest;
import com.hasan.budget.planning.domain.decision.TicketEstimate;
import com.hasan.budget.planning.domain.surplus.CategoryLine;
import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The second product surface: "can I afford this today?" and "give me more for that".
 *
 * <p>Both answers are computed by pure domain code that has no idea what a city or a user is. What
 * this class does is the same job {@link PlanAssembler} does for the plan - resolve every figure the
 * domain needs, strip it of provenance, and hand it over - so the two surfaces can never disagree
 * about what the user's groceries cost.
 */
public final class DecisionService {

    /**
     * What the user's own card payments say they pay, per merchant. Empty until bank data exists:
     * the aggregate the ingestion module is asked to expose has not been built, so every price band
     * is an estimate and says so. That is the cold-start case the decision engine already handles
     * honestly - nothing here pretends otherwise.
     */
    private static final List<ObservedTicket> NO_CARD_PAYMENTS_YET = List.of();

    private final PlanService plans;
    private final Clock clock;

    public DecisionService(PlanService plans, Clock clock) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // --- can I afford this? ------------------------------------------------------------------------

    /**
     * @param band a meal band, for eating out; null when the user priced something themselves
     * @param label and {@code price} name and price a one-off purchase; both null when a band is given
     * @param spentThisMonth what has already gone on this category this month. Asked for because no
     *     bank is connected to work it out, and never defaulted: assuming zero would make every answer
     *     look more affordable than it is.
     */
    public record AffordQuestion(
            SpendCategory category, SpendBand band, String label, Money price, Money spentThisMonth) {

        public AffordQuestion {
            Objects.requireNonNull(category, "category");
            if (spentThisMonth == null) {
                throw new IllegalArgumentException(
                        "Tell us how much has already gone on this this month, so the answer is about your real month.");
            }
            if ((band == null) == (price == null)) {
                throw new IllegalArgumentException(
                        "Pick a kind of meal, or give the price of what you are thinking of buying - one or the other.");
            }
            if (price != null && (label == null || label.isBlank())) {
                throw new IllegalArgumentException("Say what you are thinking of buying, so the answer can name it.");
            }
        }
    }

    /** @param allowanceBasis where the month's allowance came from, in a person's words */
    public record Affordability(SpendAssessment assessment, String allowanceBasis) {}

    public Affordability afford(String userId, AffordQuestion question) {
        AssembledPlan plan = plans.assemble(userId);
        SpendCategory category = question.category();
        Allowance allowance = allowanceFor(plan, category);

        PriceLadder ladder = category == SpendCategory.DINING_OUT
                ? PriceLadder.fromDiningBaseline(diningBaseline(plan, allowance), NO_CARD_PAYMENTS_YET)
                : PriceLadder.none();
        TicketEstimate purchase;
        if (question.band() != null) {
            if (category != SpendCategory.DINING_OUT) {
                throw new IllegalArgumentException(
                        "Kinds of meal only apply to eating out. For anything else, give its price.");
            }
            purchase = ladder.forBand(question.band()).orElseThrow();
        } else {
            purchase = TicketEstimate.stated(question.label().strip(), "the price you gave us", question.price());
        }

        SpendAssessment assessment = SpendDecision.decide(new SpendDecisionRequest(
                category, allowance.amount(), question.spentThisMonth(), purchase, ladder, LocalDate.now(clock)));
        return new Affordability(assessment, allowance.basis());
    }

    private record Allowance(Money amount, String basis) {}

    /**
     * The month's allowance for one category. For fun money it is that category's share of the least
     * the user wants to spend on enjoying life; for everything else, the local figure - which is the
     * user's own where they gave one, because their figure outranks every other source.
     */
    private static Allowance allowanceFor(AssembledPlan plan, SpendCategory category) {
        if (category.baselinePolicy() == BaselinePolicy.DISCRETIONARY) {
            Money share = plan.floor().perCategory().get(category);
            if (share != null) {
                return new Allowance(share, "The part of what you set aside for enjoying life that goes on "
                        + category.label().toLowerCase(java.util.Locale.ENGLISH) + ".");
            }
        }
        ResolvedBaseline figure = plan.provenance().get(category);
        if (figure == null) {
            throw new NeedsMoreInformationException(
                    "We have no monthly figure for " + category.label().toLowerCase(java.util.Locale.ENGLISH)
                            + " yet. Tell us what you usually spend on it first.");
        }
        return new Allowance(figure.amount(), figure.confidence().label() + ": " + figure.confidence().meaning());
    }

    private static Money diningBaseline(AssembledPlan plan, Allowance allowance) {
        ResolvedBaseline dining = plan.provenance().get(SpendCategory.DINING_OUT);
        return dining == null ? allowance.amount() : dining.amount();
    }

    // --- give me more for this ---------------------------------------------------------------------

    /**
     * @param amount dollars; null when the user gave a percentage
     * @param percentOfBalance a whole percentage of the balance in scope; null when they gave dollars
     */
    public record RebalanceQuestion(String raise, Money amount, Integer percentOfBalance) {

        public RebalanceQuestion {
            if (raise == null || raise.isBlank()) {
                throw new IllegalArgumentException("Say which line to give more to.");
            }
            if ((amount == null) == (percentOfBalance == null)) {
                throw new IllegalArgumentException(
                        "Give the increase in dollars or as a percentage of your balance - one or the other.");
            }
        }
    }

    public RebalanceResult rebalance(String userId, RebalanceQuestion question) {
        AssembledPlan plan = plans.assemble(userId);
        List<BudgetLine> lines = budgetLines(plan);
        if (lines.stream().noneMatch(line -> line.id().equals(question.raise()))) {
            throw new NotFoundException("Your plan has no line called \"" + question.raise() + "\".");
        }
        Share increase = question.amount() != null
                ? Share.ofAmount(question.amount())
                : Share.ofPercent(question.percentOfBalance(), plan.inputs().funds().consideredBalance());
        if (!increase.amount().isPositive()) {
            throw new IllegalArgumentException("The increase has to be more than zero.");
        }
        return Rebalancing.apply(RebalanceRequest.raise(lines, question.raise(), increase));
    }

    /**
     * The month's plan as lines that can move: every category as spent, and every named item as its
     * own line. An item named inside a category is taken out of that category's line, so the plan's
     * total is exactly what the user spends and no dollar can move twice.
     *
     * <p>Each line's floor is its local figure, resolved here and passed in bare, and its rigidity is
     * the line's own - so a gym the user locked stays locked here too.
     */
    static List<BudgetLine> budgetLines(AssembledPlan plan) {
        Map<SpendCategory, Money> namedWithin = NamedItems.withinCategories(plan.inputs().lineItems());
        List<BudgetLine> lines = new ArrayList<>();
        for (CategoryLine line : plan.breakdown().lines()) {
            if (line.lineItemId() != null) {
                lines.add(new BudgetLine(line.lineItemId(), line.label(), line.category(), line.actual(), Money.ZERO, line.rigidity()));
                continue;
            }
            Money named = namedWithin.getOrDefault(line.category(), Money.ZERO);
            Money rest = line.actual().minus(named).max(Money.ZERO);
            Money floor = line.baseline() == null ? Money.ZERO : line.baseline().minus(named).max(Money.ZERO);
            lines.add(new BudgetLine(PlanAssembler.lineId(line), line.label(), line.category(), rest, floor, line.rigidity()));
        }
        return lines;
    }
}
