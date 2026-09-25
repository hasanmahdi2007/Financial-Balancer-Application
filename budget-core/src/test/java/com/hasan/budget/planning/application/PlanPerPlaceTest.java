package com.hasan.budget.planning.application;

import static com.hasan.budget.planning.application.PlanningFixture.AUSTIN;
import static com.hasan.budget.planning.application.PlanningFixture.BEIRUT;
import static com.hasan.budget.planning.application.PlanningFixture.LEBANON;
import static com.hasan.budget.planning.application.PlanningFixture.TRIPOLI;
import static com.hasan.budget.planning.application.PlanningFixture.US;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.planning.application.PlanService.NewPlan;
import com.hasan.budget.planning.application.PlanService.PlanChoice;
import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A plan belongs to a place (P11). Moving never re-prices the old plan with the new place's figures:
 * it resumes a plan the user already had in that country, or starts a new one.
 */
class PlanPerPlaceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 14);
    private static final LocalDate NEXT_YEAR = LocalDate.of(2027, 3, 1);

    private final PlanningFixture fixture = new PlanningFixture(TODAY);
    private final PlanService plans = fixture.plans();

    /** Onboarded in Beirut with a car goal, a named gym, and one plan made. Returns the Beirut plan's id. */
    private String livingInBeirut(String userId) {
        fixture.onboard(userId, Money.of(1000));
        plans.addGoal(userId, new PlanService.GoalChange("Car", Money.of(9000), NEXT_YEAR, Priority.HIGH));
        plans.saveLineItem(userId, UserLineItem.onTopOf(
                "my-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(40)));
        return plans.plans(userId, Optional.empty()).plans().getFirst().id();
    }

    @Nested
    @DisplayName("changing where a plan is for")
    class ChangingPlace {

        @Test
        void aPlanThatHasBeenMadeIsNeverRePricedForAnotherPlace() {
            livingInBeirut("mover");
            PlanView beirut = plans.latest("mover").orElseThrow();

            assertThatThrownBy(() -> plans.saveProfile("mover", new PlanService.ProfileChange(
                            US, AUSTIN, null, LifestyleTier.REGULAR, true, null)))
                    .isInstanceOf(ChooseAPlanException.class)
                    .hasMessageContaining("Beirut, Lebanon")
                    .hasMessageContaining("Austin, United States");

            // Refused, and nothing moved: the Beirut plan is still in use, still for Beirut.
            assertThat(plans.profile("mover").orElseThrow().city()).isEqualTo(BEIRUT);
            assertThat(plans.latest("mover")).contains(beirut);
        }

        /** Nothing has been shown yet, so this is fixing an answer rather than moving. */
        @Test
        void aPlanStillBeingSetUpMayChangeItsPlace() {
            plans.saveProfile("new", new PlanService.ProfileChange(LEBANON, BEIRUT, null, null, true, null));

            plans.saveProfile("new", new PlanService.ProfileChange(LEBANON, TRIPOLI, null, null, true, null));

            assertThat(plans.profile("new").orElseThrow().city()).isEqualTo(TRIPOLI);
            assertThat(plans.plans("new", Optional.empty()).plans()).hasSize(1);
        }

        /** Changing how they live, without changing where, is not a move. */
        @Test
        void changingEverythingButThePlaceStillWorksOnAMadePlan() {
            livingInBeirut("settled");

            plans.saveProfile("settled", new PlanService.ProfileChange(
                    LEBANON, BEIRUT, null, LifestyleTier.HOMEBODY, true, null));

            assertThat(plans.profile("settled").orElseThrow().lifestyle()).isEqualTo(LifestyleTier.HOMEBODY);
        }
    }

    @Nested
    @DisplayName("starting a plan for a new place")
    class StartingANewPlan {

        @Test
        void nothingFromTheOldPlaceComesAcrossExceptTheGoalsAndTheMoneyTheyHave() {
            livingInBeirut("mover");

            PlanChoice austin = plans.startPlan("mover", new NewPlan(US, AUSTIN, null, true));

            assertThat(austin.active()).isTrue();
            assertThat(austin.place().label()).isEqualTo("Austin, United States");
            assertThat(austin.summary()).as("no plan has been made here yet").isNull();
            assertThat(plans.profile("mover").orElseThrow().city()).isEqualTo(AUSTIN);
            assertThat(plans.spending("mover")).as("Beirut rent is not Austin rent").isEmpty();
            assertThat(plans.lineItems("mover")).isEmpty();
            assertThat(plans.goals("mover")).extracting(GoalDraft::name).containsExactly("Car");
            assertThat(plans.history("mover")).as("Austin's history starts empty").isEmpty();
            assertThat(plans.latest("mover")).isEmpty();
        }

        /** Copies, not shared rows: changing the Austin car must not quietly change the Beirut one. */
        @Test
        void broughtGoalsAreCopiesThatChangeIndependently() {
            livingInBeirut("mover");
            GoalDraft beirutCar = plans.goals("mover").getFirst();

            plans.startPlan("mover", new NewPlan(US, AUSTIN, null, true));
            GoalDraft austinCar = plans.goals("mover").getFirst();

            assertThat(austinCar.id()).isNotEqualTo(beirutCar.id());
            assertThatThrownBy(() -> plans.updateGoal("mover", beirutCar.id(), new PlanService.GoalChange(
                            "Car", Money.of(1), NEXT_YEAR, Priority.LOW)))
                    .as("the Beirut goal is not reachable from the Austin plan")
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void goalsStayBehindWhenTheUserSaysSo() {
            livingInBeirut("mover");

            plans.startPlan("mover", new NewPlan(US, AUSTIN, null, false));

            assertThat(plans.goals("mover")).isEmpty();
        }

        @Test
        void theGoalChosenToFinishFirstIsStillTheOneChosenAfterTheMove() {
            livingInBeirut("mover");
            plans.addGoal("mover", new PlanService.GoalChange("Laptop", Money.of(1500), NEXT_YEAR, Priority.LOW));
            GoalDraft laptop = plans.goals("mover").stream().filter(g -> g.name().equals("Laptop")).findFirst().orElseThrow();
            plans.finishFirst("mover", Optional.of(laptop.id()));

            plans.startPlan("mover", new NewPlan(US, AUSTIN, null, true));

            String first = plans.finishFirst("mover").orElseThrow();
            assertThat(plans.goals("mover")).filteredOn(goal -> goal.id().equals(first))
                    .extracting(GoalDraft::name)
                    .containsExactly("Laptop");
        }

        /**
         * The balance is the person's and follows them; what arrives each month is the plan's, because a
         * move usually changes it, so the new plan asks for it again.
         */
        @Test
        void theBalanceComesAlongButTheIncomeIsAskedForAgain() {
            livingInBeirut("mover");

            plans.startPlan("mover", new NewPlan(US, AUSTIN, null, true));
            assertThat(plans.money("mover")).as("no income stated for Austin yet").isEmpty();

            plans.saveMoney("mover", new StatedMoney(Money.of(5000), Money.of(1000)));
            assertThat(plans.money("mover").orElseThrow().monthlyIncome()).isEqualTo(Money.of(5000));
        }

        @Test
        void aPlaceWeHoldNoFiguresForIsRefusedInWords() {
            assertThatThrownBy(() -> plans.startPlan("mover", new NewPlan(
                            new com.hasan.budget.shared.CountryCode("FR"), null, "Paris", true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Choose a country from the list");
            assertThat(plans.plans("mover", Optional.empty()).plans()).isEmpty();
        }
    }

    @Nested
    @DisplayName("going back to a plan")
    class GoingBack {

        @Test
        void theOldPlanComesBackExactlyAsItWasLeft() {
            String beirut = livingInBeirut("mover");
            PlanView lastBeirutPlan = plans.latest("mover").orElseThrow();
            Map<SpendCategory, Money> beirutSpending = plans.spending("mover");
            var beirutHistory = plans.history("mover");
            plans.startPlan("mover", new NewPlan(US, AUSTIN, null, true));

            PlanChoice back = plans.usePlan("mover", beirut);

            assertThat(back.active()).isTrue();
            assertThat(plans.profile("mover").orElseThrow().city()).isEqualTo(BEIRUT);
            assertThat(plans.spending("mover")).isEqualTo(beirutSpending);
            assertThat(plans.lineItems("mover")).extracting(UserLineItem::id).containsExactly("my-gym");
            assertThat(plans.latest("mover")).as("the plan it last made, not a new one").contains(lastBeirutPlan);
            assertThat(plans.history("mover")).isEqualTo(beirutHistory);
        }

        /** Going back is a choice, not a change, so history gains no entry that says nothing. */
        @Test
        void switchingPlansTakesNoSnapshot() {
            String beirut = livingInBeirut("mover");
            int before = plans.history("mover").size();
            plans.startPlan("mover", new NewPlan(US, AUSTIN, null, true));

            plans.usePlan("mover", beirut);

            assertThat(plans.history("mover")).hasSize(before);
        }

        @Test
        void theNextPlanMadeIsForThePlaceThatIsInUse() {
            String beirut = livingInBeirut("mover");
            plans.startPlan("mover", new NewPlan(US, AUSTIN, null, true));
            plans.usePlan("mover", beirut);

            PlanView fresh = plans.plan("mover");

            assertThat(fresh.place().label()).isEqualTo("Beirut, Lebanon");
            assertThat(fresh.place().city().id()).isEqualTo("beirut");
            assertThat(plans.history("mover").getFirst().place().label()).isEqualTo("Beirut, Lebanon");
        }
    }

    @Nested
    @DisplayName("choosing between plans")
    class Choosing {

        @Test
        void theChooserListsOnlyThePlansInTheCountryAskedAbout() {
            livingInBeirut("mover");
            plans.startPlan("mover", new NewPlan(US, AUSTIN, null, true));

            var inLebanon = plans.plans("mover", Optional.of(LEBANON)).plans();
            var everywhere = plans.plans("mover", Optional.empty()).plans();

            assertThat(inLebanon).extracting(choice -> choice.place().label()).containsExactly("Beirut, Lebanon");
            assertThat(everywhere).hasSize(2);
        }

        /** "In this country" means the whole country, each plan saying which city it is for. */
        @Test
        void plansInOtherCitiesOfTheSameCountryAreOffered() {
            livingInBeirut("mover");
            plans.startPlan("mover", new NewPlan(LEBANON, TRIPOLI, null, true));

            assertThat(plans.plans("mover", Optional.of(LEBANON)).plans())
                    .extracting(choice -> choice.place().city().name())
                    .containsExactlyInAnyOrder("Beirut", "Tripoli");
        }

        @Test
        void eachPlanSaysWhatItLastShowedAndWhatUsingItDoes() {
            livingInBeirut("mover");
            plans.startPlan("mover", new NewPlan(US, AUSTIN, null, true));

            PlanChoice beirut = plans.plans("mover", Optional.of(LEBANON)).plans().getFirst();

            assertThat(beirut.active()).isFalse();
            assertThat(beirut.summary().goals()).isEqualTo(1);
            assertThat(beirut.summary().goalsLabel()).isEqualTo("1 goal");
            assertThat(beirut.summary().leftEachMonth()).isNotBlank();
            assertThat(beirut.use().label()).isEqualTo("Use this plan");
            assertThat(beirut.use().meaning()).contains("Beirut").contains("money you have now stays");
        }

        @Test
        void theMostRecentlyUsedPlanComesFirst() {
            String beirut = livingInBeirut("mover");
            plans.startPlan("mover", new NewPlan(LEBANON, TRIPOLI, null, true));
            plans.usePlan("mover", beirut);

            assertThat(plans.plans("mover", Optional.of(LEBANON)).plans())
                    .extracting(choice -> choice.place().city().name())
                    .containsExactly("Beirut", "Tripoli");
        }
    }

    @Nested
    @DisplayName("whose plans")
    class Authorization {

        @Test
        void aUserCannotListUseOrReachAnotherUsersPlan() {
            String theirs = livingInBeirut("user-a");

            assertThat(plans.plans("user-b", Optional.empty()).plans()).isEmpty();
            assertThatThrownBy(() -> plans.usePlan("user-b", theirs)).isInstanceOf(NotFoundException.class);
            assertThat(plans.latest("user-b")).isEmpty();
            assertThat(plans.profile("user-a").orElseThrow().city()).isEqualTo(BEIRUT);
        }

        @Test
        void aPlanIdThatDoesNotExistIsNotFound() {
            livingInBeirut("mover");

            assertThatThrownBy(() -> plans.usePlan("mover", "no-such-plan"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("no-such-plan");
        }
    }
}
