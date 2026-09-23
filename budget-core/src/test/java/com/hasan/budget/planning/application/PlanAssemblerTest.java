package com.hasan.budget.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.Staleness;
import com.hasan.budget.planning.application.AssembledPlan.Reduction;
import com.hasan.budget.planning.application.CutCandidates.SuggestedCut;
import com.hasan.budget.planning.domain.AllocationRequest;
import com.hasan.budget.planning.domain.AllocationResult;
import com.hasan.budget.planning.domain.AllocationStatus;
import com.hasan.budget.planning.domain.AllocationStrategy;
import com.hasan.budget.planning.domain.DiscretionarySpend;
import com.hasan.budget.planning.domain.GoalAllocation;
import com.hasan.budget.planning.domain.GreedyPriorityAllocator;
import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.planning.domain.Tradeoff;
import com.hasan.budget.planning.domain.surplus.CategoryLine;
import com.hasan.budget.planning.domain.surplus.ItemScope;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.profile.domain.DiscretionaryFloor;
import com.hasan.budget.profile.domain.ManualConsideredFunds;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The composition of surplus, earmarking and allocation, with no container and no database.
 *
 * <p>Each piece was already correct on its own. What is under test here is the join, because that is
 * where the plan used to lie.
 */
class PlanAssemblerTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 15);
    private static final LocalDate GATHERED = LocalDate.of(2025, 11, 1);

    /** No bank connected: every figure is one the user typed, or the city's for what they did not. */
    private static final MeasuredSpending NO_BANK = MeasuredSpending.none(YearMonth.from(AS_OF).minusMonths(1));

    /** A fixed $300 floor, stated outright so the composition is what varies, not the floor policy. */
    private static final PlanAssembler.FloorSource THREE_HUNDRED =
            request -> new DiscretionaryFloor(Money.of(300), Map.of(), "test floor", false, Money.of(1000));

    private final PlanAssembler assembler = new PlanAssembler(new GreedyPriorityAllocator(), THREE_HUNDRED);

    @Nested
    @DisplayName("following the plan really reaches the goals")
    class Composition {

        /**
         * The scenario the brief measured on the merged code: $2,070 of surplus, $1,500 of real cash,
         * goals needing $2,250. Joined naively the allocator proposes $180 from observed fun money,
         * reports no gap, and a user who does exactly that lands $570 short.
         *
         * <p>This asserts the naive join really is wrong, so the fix below is measured against a
         * failure rather than a supposition.
         */
        @Test
        void theNaiveJoinProposesCutsThatLeaveTheUserShort() {
            AssembledPlan plan = assembler.assemble(measuredScenario(Money.ZERO, Money.of(10_500)));

            List<DiscretionarySpend> observedSpend = plan.breakdown().lines().stream()
                    .filter(CategoryLine::isCuttable)
                    .map(line -> new DiscretionarySpend(line.category(), line.actual(), line.rigidity()))
                    .toList();
            AllocationResult naive = new GreedyPriorityAllocator().allocate(new AllocationRequest(
                    plan.breakdown().surplus(), goalsOf(plan), observedSpend, AS_OF));

            Money naiveCuts = naive.tradeoffs().stream().map(Tradeoff::suggestedReduction).reduce(Money.ZERO, Money::plus);
            assertThat(naiveCuts).isEqualTo(Money.of(180));
            assertThat(naive.residualGap()).isEqualTo(Money.ZERO);

            Money cashAfterNaiveCuts = realSpendingBeforeAnyChange(plan).cashLeft(plan).plus(naiveCuts);
            assertThat(cashAfterNaiveCuts).isEqualTo(Money.of(1680));
            assertThat(cashAfterNaiveCuts).isLessThan(needed(plan));
        }

        /**
         * The composition test the brief asked for. The goals are short; the user does exactly what
         * the plan tells them - the reductions it already assumed, and the cuts it suggests on top - and
         * their real cash must reach what the goals need, less only the gap the plan openly admits.
         */
        @Test
        void followingOnlyWhatThePlanSaysReachesWhatTheGoalsNeed() {
            AssembledPlan plan = assembler.assemble(measuredScenario(Money.ZERO, Money.of(10_500)));

            assertThat(plan.breakdown().surplus()).isEqualTo(Money.of(2070));
            assertThat(plan.breakdown().assumedReduction()).isEqualTo(Money.of(570));
            assertThat(needed(plan)).isEqualTo(Money.of(2250));

            // Only the subscriptions line has headroom; fun money above the floor and groceries above
            // the local figure were already assumed away, so they are not offered twice.
            assertThat(plan.suggestedCuts()).extracting(SuggestedCut::category)
                    .containsOnly(SpendCategory.SUBSCRIPTIONS);
            assertThat(plan.suggestedTotal()).isEqualTo(Money.of(120));
            assertThat(plan.totalChange()).isEqualTo(Money.of(690));
            assertThat(plan.allocation().residualGap())
                    .as("the part no honest cut can find is admitted, not hidden")
                    .isEqualTo(Money.of(60));

            assertThat(cashAfterFollowing(plan).plus(plan.allocation().residualGap())).isEqualTo(needed(plan));
        }

        /** And where the headroom is enough, following the plan lands on the goals with no gap at all. */
        @Test
        void whereTheHeadroomIsEnoughFollowingThePlanLandsExactlyOnTheGoals() {
            PlanningInputs inputs = measuredScenario(Money.ZERO, Money.of(9_900));
            Map<SpendCategory, Money> spending = new EnumMap<>(inputs.statedSpending());
            spending.put(SpendCategory.SUBSCRIPTIONS, Money.of(300));
            AssembledPlan plan = assembler.assemble(withSpending(inputs, spending, List.of()));

            assertThat(plan.allocation().residualGap()).isEqualTo(Money.ZERO);
            assertThat(plan.suggestedTotal()).isEqualTo(Money.of(320));
            assertThat(cashAfterFollowing(plan)).isEqualTo(needed(plan));
        }

        /** Both numbers, always: the cuts on top, and the reduction the surplus already counted on. */
        @Test
        void theReductionsAlreadyAssumedAreStatedLineByLineAndAddUp() {
            AssembledPlan plan = assembler.assemble(measuredScenario(Money.ZERO, Money.of(10_500)));

            assertThat(plan.reductions()).extracting(Reduction::label)
                    .containsExactly("Groceries", "Eating out, Going out and fun");
            assertThat(plan.reductions()).extracting(Reduction::by)
                    .containsExactly(Money.of(250), Money.of(320));
            assertThat(plan.reductions().stream().map(Reduction::by).reduce(Money.ZERO, Money::plus))
                    .isEqualTo(plan.breakdown().assumedReduction());
        }

        /** A gym the user marked as unchangeable reaches the allocator unchangeable. */
        @Test
        void aLockedLineIsNeverSuggestedAndIsNotOfferedThroughItsCategory() {
            PlanningInputs inputs = measuredScenario(Money.ZERO, Money.of(10_500));
            UserLineItem lockedGym = new UserLineItem(
                    "gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45), Rigidity.LOCKED,
                    ItemScope.ALREADY_COUNTED);
            AssembledPlan plan = assembler.assemble(withSpending(inputs, inputs.statedSpending(), List.of(lockedGym)));

            assertThat(plan.suggestedCuts()).flatExtracting(SuggestedCut::lineIds).doesNotContain("gym");
            // Only the $15 of subscriptions that is not the gym is on offer.
            assertThat(plan.suggestedTotal()).isEqualTo(Money.of(15));
            assertThat(plan.hints()).extracting(AssembledPlan.Hint::lineId).contains("gym");
        }

        /**
         * Two candidates in one category with different answers from the user come back from the
         * allocator as two bare category cuts. Each must still be named as the user named it.
         */
        @Test
        void cutsInTheSameCategoryKeepTheUsersOwnNames() {
            PlanningInputs inputs = measuredScenario(Money.ZERO, Money.of(10_500));
            UserLineItem streaming = new UserLineItem(
                    "streaming", "Streaming", SpendCategory.SUBSCRIPTIONS, Money.of(20), Rigidity.DISPOSABLE,
                    ItemScope.ALREADY_COUNTED);
            AssembledPlan plan = assembler.assemble(withSpending(inputs, inputs.statedSpending(), List.of(streaming)));

            assertThat(plan.suggestedCuts()).extracting(SuggestedCut::label)
                    .containsExactly("Streaming", "Subscriptions");
            assertThat(plan.suggestedCuts()).extracting(SuggestedCut::amount)
                    .containsExactly(Money.of(20), Money.of(40));
        }

        /** A category the user never mentioned is taken at its local figure, and the plan says so. */
        @Test
        void unstatedCategoriesAreAssumedAtTheLocalFigureAndMarkedAsSuch() {
            PlanningInputs inputs = measuredScenario(Money.ZERO, Money.of(10_500));
            Map<SpendCategory, ResolvedBaseline> baselines = new EnumMap<>(inputs.baselines());
            baselines.put(SpendCategory.CLOTHING, baseline("90"));
            AssembledPlan plan = assembler.assemble(withBaselines(inputs, baselines));

            assertThat(plan.assumedSpending()).containsExactly(SpendCategory.CLOTHING);
            assertThat(plan.breakdown().lines())
                    .filteredOn(line -> line.category() == SpendCategory.CLOTHING)
                    .extracting(CategoryLine::actual)
                    .containsExactly(Money.of(90));
        }

        /**
         * The brief words the discretionary arm as "the headroom is what observed spending exceeds
         * the floor". Followed literally that reintroduces the very double count the rest of it is
         * about, and this test is what says so rather than a comment claiming it.
         *
         * <p>The fun-money reduction is already inside {@code assumedReduction}: the surplus charged
         * the floor and nothing more, so the $320 above it has been counted once already. Offer that
         * same $320 to the allocator and the plan asks for a bigger reduction than the largest one
         * the user could make while keeping fun money at the floor they were promised we would never
         * go below - and P7 states outright that balancing the arithmetic that way is the one thing
         * the engine must never do.
         */
        @Test
        void offeringFunMoneyAsHeadroomWouldAskForACutNobodyCanMake() {
            AssembledPlan plan = assembler.assemble(measuredScenario(Money.ZERO, Money.of(10_500)));

            // The most the user can change without going below a local figure or below the floor.
            Money withoutBreakingAPromise = plan.breakdown().assumedReduction().plus(takeAsIsHeadroom(plan));
            assertThat(plan.totalChange())
                    .as("the plan only ever asks for changes that can actually be made")
                    .isEqualTo(withoutBreakingAPromise);

            Money funAboveTheFloor = Money.of(320);
            assertThat(plan.reductions()).extracting(Reduction::by).contains(funAboveTheFloor);

            List<DiscretionarySpend> withFunOffered = new ArrayList<>(
                    CutCandidates.from(plan.breakdown().lines(), plan.inputs().lineItems()).forAllocator());
            withFunOffered.add(new DiscretionarySpend(SpendCategory.DINING_OUT, funAboveTheFloor, Rigidity.DISPOSABLE));
            AllocationResult literalReading = new GreedyPriorityAllocator().allocate(new AllocationRequest(
                    plan.breakdown().surplus(), goalsOf(plan), withFunOffered, AS_OF));

            Money cuts = literalReading.tradeoffs().stream()
                    .map(Tradeoff::suggestedReduction)
                    .reduce(Money.ZERO, Money::plus);
            Money asked = plan.breakdown().assumedReduction().plus(cuts);
            assertThat(asked)
                    .as("the same dollars, offered once as an assumption and again as a cut")
                    .isGreaterThan(withoutBreakingAPromise);
            assertThat(asked.minus(withoutBreakingAPromise))
                    .as("and the difference could only be found below the floor")
                    .isEqualTo(Money.of(60));
        }
    }

    @Nested
    @DisplayName("where each spending figure came from")
    class Provenance {

        /** With nothing said and nothing connected, the city's figure stands and the plan says so. */
        @Test
        void aCategoryNobodyCoveredFallsBackToTheLocalFigure() {
            AssembledPlan plan = assembler.assemble(withoutGroceries(measuredScenario(Money.ZERO, Money.of(10_500))));

            assertThat(plan.assumedSpending()).contains(SpendCategory.GROCERIES);
            assertThat(plan.measuredSpending()).doesNotContain(SpendCategory.GROCERIES);
            assertThat(spentOn(plan, SpendCategory.GROCERIES)).isEqualTo(Money.of(450));
        }

        /** A connected bank beats a city average, because it is this user's own account. */
        @Test
        void aBankAnswersForACategoryTheUserNeverStated() {
            PlanningInputs inputs = withoutGroceries(measuredScenario(Money.ZERO, Money.of(10_500)));
            AssembledPlan plan = assembler.assemble(
                    withMeasured(inputs, Map.of(SpendCategory.GROCERIES, Money.of(612))));

            assertThat(spentOn(plan, SpendCategory.GROCERIES)).isEqualTo(Money.of(612));
            assertThat(plan.measuredSpending()).containsExactly(SpendCategory.GROCERIES);
            assertThat(plan.assumedSpending())
                    .as("a figure read off their own account is not an assumption")
                    .doesNotContain(SpendCategory.GROCERIES);
        }

        /**
         * And the user still outranks it. They may know that last month held a wedding, or that they
         * have since moved - and nothing measured can know either.
         */
        @Test
        void whatTheUserSaidOutranksWhatTheBankRecorded() {
            PlanningInputs inputs = measuredScenario(Money.ZERO, Money.of(10_500));
            AssembledPlan plan = assembler.assemble(
                    withMeasured(inputs, Map.of(SpendCategory.GROCERIES, Money.of(612))));

            assertThat(spentOn(plan, SpendCategory.GROCERIES)).isEqualTo(Money.of(700));
            assertThat(plan.measuredSpending()).isEmpty();
        }

        /**
         * Where the figure came from changes what the plan says about it and nothing about the
         * arithmetic: a measured figure is capped at the local baseline exactly as a stated one is,
         * and the excess lands in the reduction the surplus already assumed.
         */
        @Test
        void aMeasuredFigureIsCappedLikeAnyOther() {
            PlanningInputs inputs = withoutGroceries(measuredScenario(Money.ZERO, Money.of(10_500)));
            AssembledPlan plan = assembler.assemble(
                    withMeasured(inputs, Map.of(SpendCategory.GROCERIES, Money.of(612))));

            assertThat(countedOn(plan, SpendCategory.GROCERIES)).isEqualTo(Money.of(450));
            assertThat(plan.reductions())
                    .filteredOn(reduction -> reduction.label().equals("Groceries"))
                    .extracting(Reduction::by)
                    .containsExactly(Money.of(162));
        }

        /** A bank with nothing to say about a month is not a bank saying the month cost nothing. */
        @Test
        void anEmptyMonthChangesNothing() {
            PlanningInputs inputs = withoutGroceries(measuredScenario(Money.ZERO, Money.of(10_500)));
            AssembledPlan measured = assembler.assemble(withMeasured(inputs, Map.of()));
            AssembledPlan unmeasured = assembler.assemble(inputs);

            assertThat(measured.breakdown().surplus()).isEqualTo(unmeasured.breakdown().surplus());
            assertThat(measured.assumedSpending()).isEqualTo(unmeasured.assumedSpending());
            assertThat(measured.measuredSpending()).isEmpty();
        }

        /** The month is named, so the user can check the figure against a statement rather than trust it. */
        @Test
        void aMeasuredLineSaysWhichMonthItCameFrom() {
            PlanningInputs inputs = withoutGroceries(measuredScenario(Money.ZERO, Money.of(10_500)));
            AssembledPlan plan = assembler.assemble(
                    withMeasured(inputs, Map.of(SpendCategory.GROCERIES, Money.of(612))));
            PlanView view = PlanViews.from(plan, "snapshot", Instant.EPOCH, "a test");

            assertThat(view.surplus().lines())
                    .filteredOn(line -> line.id().equals("groceries"))
                    .extracting(PlanView.Line::measuredFrom)
                    .containsExactly("what you spent in December 2025");
        }

        private PlanningInputs withoutGroceries(PlanningInputs inputs) {
            Map<SpendCategory, Money> spending = new EnumMap<>(inputs.statedSpending());
            spending.remove(SpendCategory.GROCERIES);
            return withSpending(inputs, spending, List.of());
        }

        private Money spentOn(AssembledPlan plan, SpendCategory category) {
            return lineFor(plan, category).actual();
        }

        private Money countedOn(AssembledPlan plan, SpendCategory category) {
            return lineFor(plan, category).counted();
        }

        private CategoryLine lineFor(AssembledPlan plan, SpendCategory category) {
            return plan.breakdown().lines().stream()
                    .filter(line -> line.lineItemId() == null && line.category() == category)
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    @DisplayName("earmarking the balance")
    class Earmarks {

        @Test
        void aGoalCoveredByTheBalanceNeedsNothingMonthly() {
            AssembledPlan plan = assembler.assemble(balanceScenario(Money.of(20_000), Optional.empty()));

            GoalAllocation car = allocation(plan, "car");
            assertThat(plan.earmarks().forGoal("car")).isEqualTo(Money.of(20_000));
            assertThat(car.requiredMonthly()).isEqualTo(Money.ZERO);
            assertThat(car.status()).isEqualTo(AllocationStatus.COMPLETED);
        }

        /** Without the balance, the same user is told the car is out of reach. */
        @Test
        void withoutTheBalanceTheSameCarIsFarOutOfReach() {
            AssembledPlan plan = assembler.assemble(balanceScenario(Money.ZERO, Optional.empty()));

            assertThat(allocation(plan, "car").status()).isEqualTo(AllocationStatus.AT_RISK);
        }

        @Test
        void theBalanceGoesByDescendingPriority() {
            AssembledPlan plan = assembler.assemble(balanceScenario(Money.of(20_000), Optional.empty()));

            assertThat(plan.earmarks().order()).containsExactly("car", "trip");
            assertThat(plan.earmarks().forGoal("trip")).isEqualTo(Money.ZERO);
        }

        @Test
        void aNominatedGoalTakesTheBalanceAheadOfAHigherPriorityOne() {
            AssembledPlan plan = assembler.assemble(balanceScenario(Money.of(20_000), Optional.of("trip")));

            assertThat(plan.earmarks().order()).containsExactly("trip", "car");
            assertThat(plan.earmarks().forGoal("trip")).isEqualTo(Money.of(3_000));
            assertThat(plan.earmarks().forGoal("car")).isEqualTo(Money.of(17_000));
        }

        /** Nominating a goal changes who gets the balance, never how the allocator ranks the months. */
        @Test
        void nominatingAGoalLeavesTheAllocatorsRankingAlone() {
            RecordingAllocator recording = new RecordingAllocator();
            new PlanAssembler(recording, THREE_HUNDRED)
                    .assemble(balanceScenario(Money.of(20_000), Optional.of("trip")));

            assertThat(recording.request.goals()).extracting(goal -> goal.priority())
                    .containsExactlyInAnyOrder(Priority.HIGH, Priority.LOW);
        }

        /**
         * Reversible at any time. Lowering the balance and planning again hands the money back, and
         * the goal needs more each month again.
         */
        @Test
        void takingMoneyBackOutOfScopeReturnsItToThePlanOnTheNextRun() {
            ManualConsideredFunds generous = new ManualConsideredFunds(Money.of(20_000), Money.of(6000));
            AssembledPlan before = assembler.assemble(withFunds(balanceScenario(Money.ZERO, Optional.empty()), generous));
            AssembledPlan after = assembler.assemble(withFunds(
                    balanceScenario(Money.ZERO, Optional.empty()), generous.revisedTo(Money.of(5_000))));

            assertThat(before.earmarks().forGoal("car")).isEqualTo(Money.of(20_000));
            assertThat(after.earmarks().forGoal("car")).isEqualTo(Money.of(5_000));
            assertThat(allocation(after, "car").requiredMonthly())
                    .isGreaterThan(allocation(before, "car").requiredMonthly());
        }

        /**
         * The optimistic double count, made impossible by construction: a goal has nowhere to type an
         * "already saved" figure, so what it holds can only come from the balance, and the balance is
         * handed out once.
         */
        @Test
        void theSameDollarsCanNeverReachOneGoalTwice() {
            assertThat(Arrays.stream(GoalDraft.class.getRecordComponents()).map(RecordComponent::getName))
                    .as("a goal must have no field in which to state savings independently of the balance")
                    .doesNotContain("saved", "alreadySaved", "savedSoFar");

            for (long balance : new long[] {0, 1_000, 3_000, 19_999, 20_000, 23_000, 100_000}) {
                RecordingAllocator recording = new RecordingAllocator();
                new PlanAssembler(recording, THREE_HUNDRED)
                        .assemble(balanceScenario(Money.of(balance), Optional.of("trip")));

                Money savedAcrossGoals = recording.request.goals().stream()
                        .map(goal -> goal.saved())
                        .reduce(Money.ZERO, Money::plus);
                assertThat(savedAcrossGoals)
                        .as("at a balance of %d, no more can be saved than exists", balance)
                        .isEqualTo(Money.of(balance).min(Money.of(23_000)));
                assertThat(recording.request.goals())
                        .allSatisfy(goal -> assertThat(goal.saved()).isLessThanOrEqualTo(goal.target()));
            }
        }

        /** The balance is a stock and reaches goals only through saved. The monthly surplus is untouched. */
        @Test
        void theBalanceNeverReachesTheAllocatorAsMonthlyMoney() {
            RecordingAllocator withNothing = new RecordingAllocator();
            RecordingAllocator withPlenty = new RecordingAllocator();
            new PlanAssembler(withNothing, THREE_HUNDRED).assemble(balanceScenario(Money.ZERO, Optional.empty()));
            new PlanAssembler(withPlenty, THREE_HUNDRED).assemble(balanceScenario(Money.of(100_000), Optional.empty()));

            assertThat(withPlenty.request.monthlySurplus()).isEqualTo(withNothing.request.monthlySurplus());
        }

        /** What no goal needed is the runway; what a goal holds is not also months of cover. */
        @Test
        void onlyTheUnassignedBalanceCountsAsRunway() {
            PlanningInputs inputs = balanceScenario(Money.of(23_600), Optional.empty());
            Map<SpendCategory, Money> spending = new EnumMap<>(inputs.statedSpending());
            spending.put(SpendCategory.RENT, Money.of(6_000));
            AssembledPlan plan = assembler.assemble(withSpending(inputs, spending, List.of()));

            assertThat(plan.earmarks().unassigned()).isEqualTo(Money.of(600));
            assertThat(plan.breakdown().surplus()).isEqualTo(Money.of(-300));
            assertThat(plan.runway().months()).isEqualTo(2);
        }
    }

    // --- scenarios -------------------------------------------------------------------------------

    /** The brief's measured scenario, with the car's target as the variable. */
    private static PlanningInputs measuredScenario(Money balance, Money carTarget) {
        Map<SpendCategory, Money> spending = new EnumMap<>(SpendCategory.class);
        spending.put(SpendCategory.RENT, Money.of(2400));
        spending.put(SpendCategory.HEALTHCARE, Money.of(320));
        spending.put(SpendCategory.SUBSCRIPTIONS, Money.of(60));
        spending.put(SpendCategory.UTILITIES, Money.of(180));
        spending.put(SpendCategory.GROCERIES, Money.of(700));
        spending.put(SpendCategory.TRANSPORT_FUEL, Money.of(160));
        spending.put(SpendCategory.DINING_OUT, Money.of(380));
        spending.put(SpendCategory.ENTERTAINMENT, Money.of(240));

        Map<SpendCategory, ResolvedBaseline> baselines = new EnumMap<>(SpendCategory.class);
        baselines.put(SpendCategory.RENT, baseline("2100"));
        baselines.put(SpendCategory.UTILITIES, baseline("200"));
        baselines.put(SpendCategory.GROCERIES, baseline("450"));
        baselines.put(SpendCategory.TRANSPORT_FUEL, baseline("220"));

        return new PlanningInputs(
                new ManualConsideredFunds(balance, Money.of(6000)).resolve(),
                baselines,
                spending,
                NO_BANK,
                List.of(UserLineItem.onTopOf("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(60))),
                Money.of(400),
                Optional.empty(),
                null,
                null,
                "San Francisco",
                List.of(
                        new GoalDraft("emergency", "Emergency fund", Money.of(6000), LocalDate.of(2027, 1, 15), Priority.CRITICAL),
                        new GoalDraft("car", "Car", carTarget, LocalDate.of(2026, 7, 15), Priority.HIGH)),
                Optional.empty(),
                AS_OF);
    }

    /** $6,000 a month, $3,000 of spending, a $20,000 car and a $3,000 trip. */
    private static PlanningInputs balanceScenario(Money balance, Optional<String> finishFirst) {
        Map<SpendCategory, Money> spending = new EnumMap<>(SpendCategory.class);
        spending.put(SpendCategory.RENT, Money.of(2700));
        return new PlanningInputs(
                new ManualConsideredFunds(balance, Money.of(6000)).resolve(),
                Map.of(),
                spending,
                NO_BANK,
                List.of(),
                Money.ZERO,
                Optional.empty(),
                null,
                null,
                null,
                List.of(
                        new GoalDraft("car", "Car", Money.of(20_000), LocalDate.of(2026, 7, 15), Priority.HIGH),
                        new GoalDraft("trip", "Trip", Money.of(3_000), LocalDate.of(2026, 5, 15), Priority.LOW)),
                finishFirst,
                AS_OF);
    }

    private static PlanningInputs withSpending(
            PlanningInputs in, Map<SpendCategory, Money> spending, List<UserLineItem> lineItems) {
        return new PlanningInputs(in.funds(), in.baselines(), spending, in.measuredSpending(),
                lineItems.isEmpty() ? in.lineItems() : lineItems, in.alreadySaving(), in.taxReserve(),
                in.lifestyle(), in.leastForEnjoyingLife(), in.cityLabel(), in.goals(), in.finishFirst(),
                in.asOf());
    }

    private static PlanningInputs withBaselines(PlanningInputs in, Map<SpendCategory, ResolvedBaseline> baselines) {
        return new PlanningInputs(in.funds(), baselines, in.statedSpending(), in.measuredSpending(),
                in.lineItems(), in.alreadySaving(), in.taxReserve(), in.lifestyle(), in.leastForEnjoyingLife(),
                in.cityLabel(), in.goals(), in.finishFirst(), in.asOf());
    }

    private static PlanningInputs withFunds(PlanningInputs in, ManualConsideredFunds funds) {
        return new PlanningInputs(funds.resolve(), in.baselines(), in.statedSpending(), in.measuredSpending(),
                in.lineItems(), in.alreadySaving(), in.taxReserve(), in.lifestyle(), in.leastForEnjoyingLife(),
                in.cityLabel(), in.goals(), in.finishFirst(), in.asOf());
    }

    /** The same inputs, with a connected bank having recorded the month before. */
    private static PlanningInputs withMeasured(PlanningInputs in, Map<SpendCategory, Money> measured) {
        return new PlanningInputs(in.funds(), in.baselines(), in.statedSpending(),
                new MeasuredSpending(YearMonth.from(in.asOf()).minusMonths(1), measured), in.lineItems(),
                in.alreadySaving(), in.taxReserve(), in.lifestyle(), in.leastForEnjoyingLife(), in.cityLabel(),
                in.goals(), in.finishFirst(), in.asOf());
    }

    private static ResolvedBaseline baseline(String amount) {
        return new ResolvedBaseline(Money.of(amount), Confidence.CROWDSOURCED, "test", GATHERED, Staleness.FRESH);
    }

    // --- what the user actually ends up with ------------------------------------------------------

    /**
     * Every real dollar leaving the account before the user changes anything: each category as
     * spent, anything named on top of a category, and the tax reserve. Items that only name part of
     * a category are already inside it.
     */
    private static Spend realSpendingBeforeAnyChange(AssembledPlan plan) {
        Map<String, Money> byLine = new java.util.LinkedHashMap<>();
        for (CategoryLine line : plan.breakdown().lines()) {
            boolean onTop = line.lineItemId() == null || plan.inputs().lineItems().stream()
                    .anyMatch(item -> item.id().equals(line.lineItemId()) && item.scope().isSubtractedInItsOwnRight());
            if (onTop) {
                byLine.put(PlanAssembler.lineId(line), line.actual());
            }
        }
        return new Spend(byLine);
    }

    /** Income minus real spending after doing exactly what the plan says: its reductions and its cuts. */
    private static Money cashAfterFollowing(AssembledPlan plan) {
        Spend spend = realSpendingBeforeAnyChange(plan);
        for (Reduction reduction : plan.reductions()) {
            spend = spend.lessBy(reduction.by());
        }
        for (SuggestedCut cut : plan.suggestedCuts()) {
            spend = spend.lessBy(cut.amount());
        }
        return spend.cashLeft(plan);
    }

    /**
     * The largest reduction the user could make without going below a local figure or below the
     * least they said they want for enjoying life: what the surplus already assumed, plus the
     * lines it charged in full and assumed nothing about.
     */
    private static Money takeAsIsHeadroom(AssembledPlan plan) {
        return CutCandidates.from(plan.breakdown().lines(), plan.inputs().lineItems()).total();
    }

    private static Money needed(AssembledPlan plan) {
        return plan.allocation().goalAllocations().stream()
                .map(GoalAllocation::requiredMonthly)
                .reduce(Money.ZERO, Money::plus);
    }

    private record Spend(Map<String, Money> byLine) {
        Spend lessBy(Money amount) {
            Map<String, Money> next = new java.util.LinkedHashMap<>(byLine);
            next.put("changes", next.getOrDefault("changes", Money.ZERO).minus(amount));
            return new Spend(next);
        }

        Money total() {
            return byLine.values().stream().reduce(Money.ZERO, Money::plus);
        }

        /** The cash this spending leaves out of the plan's income. */
        Money cashLeft(AssembledPlan plan) {
            return plan.inputs().funds().monthlyIncome().minus(total());
        }
    }

    private static List<com.hasan.budget.planning.domain.GoalInput> goalsOf(AssembledPlan plan) {
        List<com.hasan.budget.planning.domain.GoalInput> goals = new ArrayList<>();
        for (GoalDraft goal : plan.inputs().goals()) {
            goals.add(new com.hasan.budget.planning.domain.GoalInput(
                    goal.id(), goal.name(), goal.target(), plan.earmarks().forGoal(goal.id()), goal.deadline(), goal.priority()));
        }
        return goals;
    }

    private static GoalAllocation allocation(AssembledPlan plan, String goalId) {
        return plan.allocation().goalAllocations().stream()
                .filter(allocation -> allocation.goalId().equals(goalId))
                .findFirst()
                .orElseThrow();
    }

    /** The real allocator, with the request it was given kept for inspection. */
    private static final class RecordingAllocator implements AllocationStrategy {
        private AllocationRequest request;

        @Override
        public AllocationResult allocate(AllocationRequest request) {
            this.request = request;
            return new GreedyPriorityAllocator().allocate(request);
        }
    }
}
