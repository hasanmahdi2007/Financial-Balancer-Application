package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.planning.application.AssembledPlan.Hint;
import com.hasan.budget.planning.application.AssembledPlan.Reduction;
import com.hasan.budget.planning.application.CutCandidates.SuggestedCut;
import com.hasan.budget.planning.application.Earmarking.Earmarks;
import com.hasan.budget.planning.domain.AllocationRequest;
import com.hasan.budget.planning.domain.AllocationResult;
import com.hasan.budget.planning.domain.AllocationStrategy;
import com.hasan.budget.planning.domain.GoalInput;
import com.hasan.budget.planning.domain.decision.SavingLever;
import com.hasan.budget.planning.domain.surplus.CategoryLine;
import com.hasan.budget.planning.domain.surplus.CategoryObservation;
import com.hasan.budget.planning.domain.surplus.SurplusBreakdown;
import com.hasan.budget.planning.domain.surplus.SurplusCalculation;
import com.hasan.budget.planning.domain.surplus.SurplusInput;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.profile.domain.ConsideredFunds;
import com.hasan.budget.profile.domain.DiscretionaryFloor;
import com.hasan.budget.profile.domain.FloorRequest;
import com.hasan.budget.profile.domain.ProtectedSpending;
import com.hasan.budget.profile.domain.Runway;
import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The sole translator between cost of living, the profile and the planning math.
 *
 * <p>Three seams meet here, and each is kept narrow on purpose:
 *
 * <ol>
 *   <li><b>Provenance stops here.</b> Baselines arrive as {@link ResolvedBaseline}, carrying
 *       confidence and source, and leave as bare {@link Money}. Confidence travels to the view, never
 *       into the formula - the moment the arithmetic can see it, someone writes
 *       {@code if (confidence == ESTIMATED)} in the middle of it.
 *   <li><b>The balance reaches goals only by raising {@code saved}.</b> {@link Earmarking} hands the
 *       considered balance out before the allocator runs; the allocator still receives one monthly
 *       {@code Money} and never a {@link ConsideredFunds}.
 *   <li><b>The allocator is offered headroom, not observed spend.</b> See {@link CutCandidates} for
 *       why the naive join produces a plan that lies.
 * </ol>
 *
 * <p>Pure: no Spring, no I/O, no clock. The evaluation date and every figure arrive on
 * {@link PlanningInputs}, and the floor policy arrives as a function, so the whole composition is
 * testable in milliseconds.
 */
public final class PlanAssembler {

    private final AllocationStrategy allocator;
    private final FloorSource floors;

    public PlanAssembler(AllocationStrategy allocator, FloorSource floors) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.floors = Objects.requireNonNull(floors, "floors");
    }

    /** Where the least-for-enjoying-life figure comes from. A function so tests can state it outright. */
    @FunctionalInterface
    public interface FloorSource {
        DiscretionaryFloor floorFor(FloorRequest request);
    }

    public AssembledPlan assemble(PlanningInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        Money income = inputs.funds().monthlyIncome();

        Set<SpendCategory> assumed = EnumSet.noneOf(SpendCategory.class);
        Set<SpendCategory> measured = EnumSet.noneOf(SpendCategory.class);
        List<CategoryObservation> observations = observations(inputs, assumed, measured);

        DiscretionaryFloor floor = floors.floorFor(floorRequest(inputs, income, observations));
        SurplusBreakdown breakdown = SurplusCalculation.compute(new SurplusInput(
                income, observations, inputs.lineItems(), inputs.savingEachMonth(), floor.monthly(), inputs.asOf()));

        Earmarks earmarks = Earmarking.earmark(
                inputs.funds().consideredBalance(), inputs.goals(), inputs.finishFirst());
        List<GoalInput> goals = inputs.goals().stream()
                .map(goal -> new GoalInput(
                        goal.id(), goal.name(), goal.target(), earmarks.forGoal(goal.id()), goal.deadline(), goal.priority()))
                .toList();

        CutCandidates candidates = CutCandidates.from(breakdown.lines(), inputs.lineItems());
        AllocationResult allocation = allocator.allocate(
                new AllocationRequest(breakdown.surplus(), goals, candidates.forAllocator(), inputs.asOf()));
        List<SuggestedCut> suggested = candidates.name(allocation.tradeoffs());

        return new AssembledPlan(
                inputs,
                floor,
                breakdown,
                earmarks,
                allocation,
                suggested,
                reductions(breakdown, inputs.lineItems()),
                hints(breakdown),
                runway(inputs.funds(), earmarks, breakdown),
                assumed,
                measured,
                inputs.baselines());
    }

    /**
     * One observation per category anything at all is known about, in one order of precedence.
     *
     * <ol>
     *   <li><b>What the user said.</b> It outranks a bank, not despite the bank being measured but
     *       because of what it measures: one particular month. Only the user knows that last month
     *       held a wedding, or that they have since moved.
     *   <li><b>What a connected bank recorded for a finished month.</b> Better than an average of the
     *       city for someone who has not answered, and the plan names the month so it can be checked.
     *   <li><b>The local figure</b>, which is the honest default for someone who has told us nothing
     *       and connected nothing - and the category is recorded as assumed so the plan says so.
     * </ol>
     *
     * <p>The local figure still travels alongside as the baseline in all three cases, because it is
     * what a capped category is capped at. Where the spending figure came from changes what the plan
     * says about it, and never what the arithmetic does with it.
     */
    private static List<CategoryObservation> observations(
            PlanningInputs inputs, Set<SpendCategory> assumed, Set<SpendCategory> measured) {

        List<CategoryObservation> observations = new ArrayList<>();
        for (SpendCategory category : SpendCategory.values()) {
            if (category == SpendCategory.TAX_RESERVE) {
                continue;
            }
            ResolvedBaseline baseline = inputs.baselines().get(category);
            Money localFigure = baseline == null ? null : baseline.amount();
            Money stated = inputs.statedSpending().get(category);
            Money fromBank = inputs.measuredMonth().in(category);
            if (stated != null) {
                observations.add(new CategoryObservation(category, stated, localFigure));
            } else if (fromBank != null) {
                measured.add(category);
                observations.add(new CategoryObservation(category, fromBank, localFigure));
            } else if (localFigure != null) {
                assumed.add(category);
                observations.add(new CategoryObservation(category, localFigure, localFigure));
            }
        }
        inputs.taxReserve().ifPresent(reserve ->
                observations.add(CategoryObservation.withoutBaseline(SpendCategory.TAX_RESERVE, reserve)));
        return observations;
    }

    private FloorRequest floorRequest(
            PlanningInputs inputs, Money income, List<CategoryObservation> observations) {
        Money fixed = observations.stream()
                .filter(observation -> observation.category().baselinePolicy() == BaselinePolicy.TAKE_AS_IS)
                .map(CategoryObservation::actual)
                .reduce(Money.ZERO, Money::plus);
        for (UserLineItem item : inputs.lineItems()) {
            if (item.scope().isSubtractedInItsOwnRight()
                    && item.parent().baselinePolicy() == BaselinePolicy.TAKE_AS_IS) {
                fixed = fixed.plus(item.monthlyAmount());
            }
        }

        Map<SpendCategory, Money> protectedBaselines = new EnumMap<>(SpendCategory.class);
        for (SpendCategory category : ProtectedSpending.categories()) {
            ResolvedBaseline baseline = inputs.baselines().get(category);
            if (baseline != null) {
                protectedBaselines.put(category, baseline.amount());
            }
        }

        FloorRequest request = inputs.lifestyle() == null
                ? FloorRequest.withoutALifestyleTier(income, fixed)
                : FloorRequest.of(inputs.lifestyle(), income, fixed);
        // Built with a zero floor only to ask the input which declared commitments count, because that
        // filter lives next to the data and must not be re-derived here.
        SurplusInput probe = new SurplusInput(
                income, List.of(), inputs.lineItems(), inputs.savingEachMonth(), Money.ZERO, inputs.asOf());
        request = request.in(inputs.cityLabel(), protectedBaselines)
                .withDeclaredCommitments(probe.declaredDiscretionaryCommitments());
        return inputs.leastForEnjoyingLife() == null ? request : request.statedBy(inputs.leastForEnjoyingLife());
    }

    /**
     * What the surplus already counted on, as instructions a person can follow: each capped line
     * brought down to its local figure, and the fun-money lines together brought down to the floor.
     *
     * <p>The fun-money lines are one reduction rather than several, because the floor is one figure
     * rather than a per-category budget; splitting it would invent an allocation nobody chose.
     */
    private static List<Reduction> reductions(SurplusBreakdown breakdown, List<UserLineItem> lineItems) {
        List<Reduction> reductions = new ArrayList<>();
        List<CategoryLine> fun = new ArrayList<>();
        Money funSpent = Money.ZERO;
        for (CategoryLine line : breakdown.lines()) {
            boolean wholeCategory = line.lineItemId() == null;
            if (wholeCategory && line.policy() == BaselinePolicy.CAP_AT_BASELINE
                    && line.actual().compareTo(line.counted()) > 0) {
                reductions.add(new Reduction(
                        line.label(), List.of(Keys.of(line.category())), line.actual(), line.counted()));
            }
            if (line.policy() == BaselinePolicy.DISCRETIONARY && (wholeCategory || isOnTop(line, lineItems))) {
                fun.add(line);
                funSpent = funSpent.plus(line.actual());
            }
        }
        Money floor = breakdown.discretionaryFloor();
        if (funSpent.compareTo(floor) > 0) {
            reductions.add(new Reduction(
                    fun.stream().map(CategoryLine::label).distinct().collect(Collectors.joining(", ")),
                    fun.stream().map(PlanAssembler::lineId).toList(),
                    funSpent,
                    floor));
        }
        return reductions;
    }

    private static boolean isOnTop(CategoryLine line, List<UserLineItem> lineItems) {
        return lineItems.stream()
                .anyMatch(item -> item.id().equals(line.lineItemId()) && item.scope().isSubtractedInItsOwnRight());
    }

    /** A sentence for every line nobody may cut - rent, loans, tax, and whatever the user locked. */
    private static List<Hint> hints(SurplusBreakdown breakdown) {
        return breakdown.lines().stream()
                .filter(line -> line.rigidity() == Rigidity.LOCKED && line.actual().isPositive())
                .map(line -> new Hint(lineId(line), line.label(), SavingLever.forCategory(line.category()).lever()))
                .toList();
    }

    /**
     * Months of cover from the balance no goal needed. Only the unassigned part, so the same dollar is
     * never both a goal's savings and a month of runway.
     */
    private static Runway runway(ConsideredFunds funds, Earmarks earmarks, SurplusBreakdown breakdown) {
        Money deficit = breakdown.surplus().isNegative() ? Money.ZERO.minus(breakdown.surplus()) : Money.ZERO;
        ConsideredFunds unassigned = new ConsideredFunds(
                earmarks.unassigned(), funds.monthlyIncome(), funds.setAside(), funds.mode());
        return Runway.of(unassigned, deficit);
    }

    static String lineId(CategoryLine line) {
        return line.lineItemId() != null ? line.lineItemId() : Keys.of(line.category());
    }
}
