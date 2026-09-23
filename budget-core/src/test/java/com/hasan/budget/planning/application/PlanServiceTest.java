package com.hasan.budget.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the service refuses, and what it records, with no container and no database.
 *
 * <p>These are the rules that keep the API honest rather than merely working: a refusal a person can
 * act on, a snapshot that says why it exists, and an input that cannot be entered twice in two forms.
 */
class PlanServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 14);
    private static final LocalDate NEXT_YEAR = LocalDate.of(2027, 3, 1);

    private final PlanningFixture fixture = new PlanningFixture(TODAY);
    private final PlanService plans = fixture.plans();

    @Nested
    @DisplayName("what it refuses, and how it says so")
    class Refusals {

        /** Every message here is shown to the user unchanged, so each has to be a sentence they can act on. */
        @Test
        void aPlanCannotBeMadeBeforeTheUserHasSaidWhereTheyLiveAndWhatTheyEarn() {
            assertThatThrownBy(() -> plans.plan("newcomer"))
                    .isInstanceOf(NeedsMoreInformationException.class)
                    .hasMessageContaining("country and city");

            plans.saveProfile("newcomer", new PlanService.ProfileChange(
                    PlanningFixture.LEBANON, PlanningFixture.BEIRUT, null, LifestyleTier.REGULAR, true, null));
            assertThatThrownBy(() -> plans.plan("newcomer"))
                    .isInstanceOf(NeedsMoreInformationException.class)
                    .hasMessageContaining("how much comes in each month");

            plans.saveMoney("newcomer", new StatedMoney(Money.ZERO, Money.of(500)));
            assertThatThrownBy(() -> plans.plan("newcomer"))
                    .as("a plan is built from what arrives each month, so zero income is not a plan")
                    .isInstanceOf(NeedsMoreInformationException.class)
                    .hasMessageContaining("monthly income");
        }

        @Test
        void aCityWeHoldNoFiguresForIsRefusedRatherThanGuessedAt() {
            assertThatThrownBy(() -> plans.saveProfile("wanderer", new PlanService.ProfileChange(
                            new CountryCode("FR"), null, "Lyon", LifestyleTier.REGULAR, true, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Choose a country from the list");

            assertThatThrownBy(() -> plans.saveProfile("wanderer", new PlanService.ProfileChange(
                            PlanningFixture.LEBANON, new MetroId("atlantis"), null, LifestyleTier.REGULAR, true, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not in our list");
        }

        /** A city we do not list is a normal path, not a dead end: the typed name is kept for display. */
        @Test
        void aCityWeDoNotListIsRecordedByItsCountryAndShownBackByItsName() {
            PlanningProfile saved = plans.saveProfile("wanderer", new PlanService.ProfileChange(
                    PlanningFixture.LEBANON, null, "Zahle", LifestyleTier.OCCASIONAL, true, null));

            assertThat(saved.listedCity()).isEmpty();
            assertThat(saved.cityNotListed()).isEqualTo("Zahle");
        }

        @Test
        void taxSetAsideIsNeverSomethingTheUserEnters() {
            Map<SpendCategory, Money> stated = new EnumMap<>(SpendCategory.class);
            stated.put(SpendCategory.TAX_RESERVE, Money.of(200));

            assertThatThrownBy(() -> plans.saveSpending("freelancer", stated))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("worked out from your tax rate");
        }

        /**
         * Plan lines are named in one flat space, so an item calling itself "rent" would collide with
         * the rent line and leave rebalancing unable to say which one moved.
         */
        @Test
        void aNamedItemCannotTakeTheNameOfAKindOfSpending() {
            assertThatThrownBy(() -> plans.saveLineItem("saver", UserLineItem.onTopOf(
                            "rent", "Parking space", SpendCategory.RENT, Money.of(50))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already the name of a kind of spending");

            assertThatThrownBy(() -> plans.saveLineItem("saver", UserLineItem.onTopOf(
                            "transport-fuel", "Season ticket", SpendCategory.TRANSPORT_FUEL, Money.of(50))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * The calculation rejects an item named inside a category it does not fit in, in words meant
         * for whoever reads a stack trace - constant name and all. The user is told which two figures
         * of their own disagree instead.
         */
        @Test
        void anItemNamedInsideACategoryHasToFitInsideIt() {
            fixture.onboard("saver", Money.of(1000));
            plans.saveSpending("saver", Map.of(SpendCategory.SUBSCRIPTIONS, Money.of(40)));
            plans.saveLineItem("saver", UserLineItem.alreadyIn(
                    "gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45)));

            assertThatThrownBy(() -> plans.plan("saver"))
                    .isInstanceOf(NeedsMoreInformationException.class)
                    .hasMessageContaining("comes to 45.00 a month")
                    .hasMessageContaining("more than the 40.00")
                    .hasMessageNotContainingAny("SUBSCRIPTIONS", "ALREADY_COUNTED");
        }

        /** And naming part of something they have never told us they spend on says exactly that. */
        @Test
        void anItemNamedInsideACategoryWithNoFigureAsksForTheFigure() {
            fixture.onboard("saver", Money.of(1000));
            plans.saveSpending("saver", Map.of(SpendCategory.RENT, Money.of(600)));
            plans.saveLineItem("saver", UserLineItem.alreadyIn(
                    "gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45)));

            assertThatThrownBy(() -> plans.plan("saver"))
                    .isInstanceOf(NeedsMoreInformationException.class)
                    .hasMessageContaining("not told us what you spend on subscriptions");
        }

        @Test
        void deletingSomethingThatIsNotYoursSaysOnlyThatItIsNotThere() {
            fixture.onboard("owner", Money.of(1000));
            plans.saveLineItem("owner", UserLineItem.onTopOf(
                    "gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(40)));

            assertThatThrownBy(() -> plans.deleteLineItem("stranger", "gym"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("no named item");
            assertThat(plans.lineItems("owner")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("what each plan records about why it exists")
    class Snapshots {

        @Test
        void everyChangeToAGoalAppendsASnapshotSayingWhatTheUserDid() {
            fixture.onboard("saver", Money.of(1000));
            GoalDraft car = plans.addGoal("saver", new PlanService.GoalChange(
                            "Car", Money.of(9000), NEXT_YEAR, Priority.HIGH))
                    .goal();
            plans.updateGoal("saver", car.id(), new PlanService.GoalChange(
                    "Car", Money.of(9000), LocalDate.of(2028, 3, 1), Priority.HIGH));
            plans.finishFirst("saver", Optional.of(car.id()));
            plans.finishFirst("saver", Optional.empty());
            plans.removeGoal("saver", car.id());

            assertThat(plans.history("saver")).extracting(PlanHistory.Entry::reason)
                    .containsExactly(
                            "You removed a goal: Car",
                            "You went back to finishing goals in order of importance",
                            "You chose Car to finish first",
                            "You changed a goal: Car",
                            "You added a goal: Car");
        }

        /** Removing the nominated goal must not leave a nomination pointing at nothing. */
        @Test
        void removingTheNominatedGoalTakesTheNominationWithIt() {
            fixture.onboard("saver", Money.of(1000));
            GoalDraft car = plans.addGoal("saver", new PlanService.GoalChange(
                            "Car", Money.of(9000), NEXT_YEAR, Priority.HIGH))
                    .goal();
            plans.finishFirst("saver", Optional.of(car.id()));

            plans.removeGoal("saver", car.id());

            assertThat(plans.finishFirst("saver")).isEmpty();
        }

        /**
         * A goal can be added before there is anything to plan with. It is kept, and the answer says
         * what is still missing rather than refusing the goal or inventing a plan.
         */
        @Test
        void aGoalAddedBeforeThereIsAnythingToPlanWithIsStillKept() {
            PlanService.GoalAndPlan added = plans.addGoal("newcomer", new PlanService.GoalChange(
                    "Car", Money.of(9000), NEXT_YEAR, Priority.HIGH));

            assertThat(added.goal().name()).isEqualTo("Car");
            assertThat(added.plan()).isNull();
            assertThat(added.waitingFor()).contains("country and city");
            assertThat(plans.goals("newcomer")).hasSize(1);
        }

        /** The plan a user is looking at does not change under them until they ask for a new one. */
        @Test
        void readingAPlanNeverRecomputesIt() {
            fixture.onboard("saver", Money.of(1000));
            PlanView made = plans.plan("saver");
            plans.saveMoney("saver", new StatedMoney(Money.of(5000), Money.of(90_000)));

            assertThat(plans.latest("saver")).contains(made);
            assertThat(plans.history("saver")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("what the plan is built from")
    class Inputs {

        /**
         * What the user already puts away is theirs to state, and it reaches the plan as they stated
         * it. It is never subtracted: saving is not spending, and charging them for it would make
         * their plan look worse the more they are already doing right.
         */
        @Test
        void whatTheUserAlreadyPutsAwayIsCarriedIntoThePlanAndNeverSubtracted() {
            fixture.onboard("saver", Money.of(1_000));
            plans.saveMoney("saver", new StatedMoney(Money.of(2_000), Money.of(1_000), Money.of(300)));

            AssembledPlan plan = plans.assemble("saver");
            AssembledPlan without = withoutTheSavingHabit();

            assertThat(plan.breakdown().alreadySaving()).isEqualTo(Money.of(300));
            assertThat(plan.breakdown().surplus())
                    .as("stating it changes what the plan shows and nothing about what it charges")
                    .isEqualTo(without.breakdown().surplus());
        }

        /** Nobody has to answer it, and not answering means the same plan with nothing claimed. */
        @Test
        void notAnsweringItIsAnAnswer() {
            fixture.onboard("quiet", Money.of(1_000));

            assertThat(plans.assemble("quiet").breakdown().alreadySaving()).isEqualTo(Money.ZERO);
        }

        /**
         * The month a plan reads off a bank is the last one that is over, never the one in progress.
         * Asked on the 14th, the month in progress is half a month of spending, and a plan built on it
         * would hand the user a surplus that does not exist.
         */
        @Test
        void aPlanReadsTheLastCompleteMonthAndNotTheOneInProgress() {
            RecordingBank bank = new RecordingBank(Map.of(SpendCategory.UTILITIES, Money.of(140)));
            PlanningFixture connected = new PlanningFixture(TODAY, bank);
            connected.onboard("banked", Money.of(1_000));

            AssembledPlan plan = connected.plans().assemble("banked");

            assertThat(bank.asked).isEqualTo(YearMonth.of(2026, 2));
            assertThat(bank.asked).isNotEqualTo(YearMonth.from(TODAY));
            assertThat(plan.measuredSpending()).contains(SpendCategory.UTILITIES);
        }

        private AssembledPlan withoutTheSavingHabit() {
            PlanningFixture plain = new PlanningFixture(TODAY);
            plain.onboard("plain", Money.of(1_000));
            return plain.plans().assemble("plain");
        }
    }

    /** A bank that records which month it was asked about, which is the whole point of these tests. */
    private static final class RecordingBank implements BankSpending {

        private final Map<SpendCategory, Money> month;
        private YearMonth asked;

        private RecordingBank(Map<SpendCategory, Money> month) {
            this.month = month;
        }

        @Override
        public java.util.List<com.hasan.budget.planning.domain.decision.ObservedTicket> observedTickets(
                String userId, SpendCategory category, YearMonth month) {
            return java.util.List.of();
        }

        @Override
        public Optional<Money> spentThisMonth(String userId, SpendCategory category, YearMonth month) {
            return Optional.empty();
        }

        @Override
        public MeasuredSpending measuredSpending(String userId, YearMonth month) {
            this.asked = month;
            return new MeasuredSpending(month, this.month);
        }
    }
}
