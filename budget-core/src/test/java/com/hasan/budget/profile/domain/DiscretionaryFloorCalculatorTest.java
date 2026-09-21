package com.hasan.budget.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The floor is what stops the engine answering "how do I buy a car in five months?" with "stop
 * going out, stop eating out, and stop buying clothes". These tests are therefore about whether the
 * advice stays liveable, not about whether the arithmetic adds up.
 */
class DiscretionaryFloorCalculatorTest {

    private final DiscretionaryFloorCalculator calculator =
            new DiscretionaryFloorCalculator(SeededFloorPolicy.policy());

    /**
     * The worked example the policy was designed around: someone out regularly, with 45% of a
     * $4,000 income already committed, keeps about $50 a week across going out, eating out and
     * clothes. Credible as a minimum rather than generous, which is the whole point of a floor.
     *
     * <p>$217.80 rather than the $216 quoted when the policy was written: that figure rounded the
     * three CEX components to a combined 12%, and these rows carry them at their published 4.6%,
     * 5.0% and 2.5%.
     */
    @Test
    void someoneOutRegularlyKeepsAboutFiftyDollarsAWeek() {
        DiscretionaryFloor floor = calculator.floorFor(
                FloorRequest.of(LifestyleTier.REGULAR, Money.of(4_000), Money.of(1_800)));

        assertThat(floor.monthly()).isEqualTo(Money.of("217.80"));
    }

    /**
     * Zero is the value the floor has when nobody has defined one, and it is the value that makes
     * the engine give advice nobody follows. No tier may reach it, however stretched the user.
     */
    @ParameterizedTest
    @EnumSource(LifestyleTier.class)
    void noTierIsEverLeftWithNothingToLiveOn(LifestyleTier tier) {
        DiscretionaryFloor floor = calculator.floorFor(
                FloorRequest.of(tier, Money.of(3_000), Money.of(2_850)));

        assertThat(floor.monthly().isPositive()).isTrue();
    }

    /**
     * The tier has to change the answer, or collecting it was pointless. Someone whose life happens
     * outside the house has more to protect than someone whose life happens in it.
     */
    @Test
    void goingOutMostDaysProtectsMoreThanStayingIn() {
        Money frequent = calculator
                .floorFor(FloorRequest.of(LifestyleTier.FREQUENT, Money.of(4_000), Money.of(1_800)))
                .monthly();
        Money homebody = calculator
                .floorFor(new FloorRequest(
                        LifestyleTier.HOMEBODY, Money.of(4_000), Money.of(1_800), Map.of(), null, null))
                .monthly();

        assertThat(frequent).isGreaterThan(homebody);
    }

    /**
     * Someone with little left after rent and loans cannot also protect a large lifestyle budget,
     * so the floor comes down - but the lower clamp stops it coming down to "live on nothing",
     * which is the advice a purely proportional rule would eventually give.
     */
    @Test
    void aStretchedUserKeepsTheLowerClampEvenAsTheFloorFalls() {
        DiscretionaryFloor comfortable = calculator.floorFor(
                FloorRequest.of(LifestyleTier.HOMEBODY, Money.of(4_000), Money.of(800)));
        DiscretionaryFloor stretched = calculator.floorFor(
                FloorRequest.of(LifestyleTier.HOMEBODY, Money.of(4_000), Money.of(3_200)));

        assertThat(stretched.monthly()).isLessThan(comfortable.monthly());
        assertThat(stretched.monthly())
                .describedAs("3% of a $4,000 income is the least the engine will leave anyone")
                .isEqualTo(Money.of(120));
    }

    /**
     * The other end of the same claim. An expensive city and an outgoing user could otherwise
     * produce a floor that swallows the surplus, at which point every goal reads as infeasible and
     * the product has nothing useful to say.
     */
    @Test
    void theFloorNeverSwallowsMoreThanTheUpperClampAllows() {
        DiscretionaryFloor floor = calculator.floorFor(new FloorRequest(
                LifestyleTier.FREQUENT,
                Money.of(4_000),
                Money.of(400),
                Map.of(
                        SpendCategory.ENTERTAINMENT, Money.of(500),
                        SpendCategory.DINING_OUT, Money.of(600),
                        SpendCategory.CLOTHING, Money.of(300)),
                null,
                null));

        assertThat(floor.monthly())
                .describedAs("unclamped this would be $885.50, which is most of the surplus")
                .isEqualTo(Money.of(480));
    }

    /**
     * The derived figure is a starting point for a question, not a decision taken on the user's
     * behalf. Once they answer, their number stands - no multiplier, and neither clamp, in either
     * direction.
     */
    @Test
    void theUsersOwnFigureReplacesTheComputedDefaultEntirely() {
        FloorRequest asked = FloorRequest.of(LifestyleTier.REGULAR, Money.of(4_000), Money.of(1_800));

        assertThat(calculator.floorFor(asked.statedBy(Money.of(350))).monthly())
                .isEqualTo(Money.of(350));
        assertThat(calculator.floorFor(asked.statedBy(Money.of(900))).monthly())
                .describedAs("above the 12% clamp, and still their answer")
                .isEqualTo(Money.of(900));
        assertThat(calculator.floorFor(asked.statedBy(Money.of(40))).monthly())
                .describedAs("below the 3% clamp, and still their answer")
                .isEqualTo(Money.of(40));
        assertThat(calculator.floorFor(asked.statedBy(Money.of(350))).userProvided()).isTrue();
    }

    /**
     * Expressing the policy as a share of each category's local baseline is what makes one row work
     * everywhere. A user in a city we hold figures for gets a floor built from those figures rather
     * than from a national average that describes nobody.
     */
    @Test
    void aKnownCityGivesAFloorBuiltFromThatCitysFigures() {
        FloorRequest request = FloorRequest.of(LifestyleTier.REGULAR, Money.of(4_000), Money.of(1_800));

        DiscretionaryFloor national = calculator.floorFor(request);
        DiscretionaryFloor beirut =
                calculator.floorFor(request.in("Beirut", SeededFloorPolicy.beirutBaselines()));

        // 45% of $160 + $220 + $90.
        assertThat(beirut.monthly()).isEqualTo(Money.of("211.50"));
        assertThat(beirut.monthly()).isNotEqualTo(national.monthly());
    }

    /**
     * The engine cuts category by category, so it needs to know how far each line may fall, not
     * only what the total must not fall below. A split that did not reconcile would show the user a
     * total that disagreed with its own parts.
     */
    @Test
    void theSplitAcrossCategoriesAddsBackUpToTheTotal() {
        DiscretionaryFloor floor = calculator.floorFor(
                FloorRequest.of(LifestyleTier.OCCASIONAL, Money.of(3_333), Money.of(1_111)));

        assertThat(floor.perCategory().keySet())
                .containsExactlyInAnyOrderElementsOf(ProtectedSpending.categories());
        assertThat(floor.perCategory().values().stream().reduce(Money.ZERO, Money::plus))
                .isEqualTo(floor.monthly());
    }

    /**
     * Rule: anything the app asks a user for explains itself. A person asked for a number needs to
     * know what it covers, why it is being asked, and what the suggestion is based on.
     */
    @Test
    void theFloorQuestionSaysWhatItCoversWhyAndOnWhatBasis() {
        SpendingQuestion question = calculator
                .floorFor(FloorRequest.of(LifestyleTier.REGULAR, Money.of(4_000), Money.of(1_800))
                        .in("Beirut", Map.of()))
                .asQuestion();

        assertThat(question.question())
                .isEqualTo("What is the least you would want to spend each month on enjoying life?");
        assertThat(question.why())
                .isEqualTo("We will never suggest cutting below this, even to reach a goal faster.");
        assertThat(question.explainedCoverage())
                .containsExactly(
                        "Going out and fun - nights out, cinema, games, sports, hobbies",
                        "Eating out - restaurants, cafes, takeaway, snacks and coffee",
                        "Clothes - clothing, shoes and personal items");
        assertThat(question.basis()).isEqualTo("typical for someone in Beirut who goes out regularly");
        assertThat(question.suggested()).isEqualTo(Money.of("217.80"));
    }

    /**
     * A user with no city we hold figures for still gets a grounded suggestion, and the grounding
     * does not claim a city it does not have.
     */
    @Test
    void aUserWithNoKnownCityStillGetsAStatedBasis() {
        DiscretionaryFloor floor = calculator.floorFor(
                FloorRequest.of(LifestyleTier.HOMEBODY, Money.of(4_000), Money.of(1_800)));

        assertThat(floor.basis()).isEqualTo("typical for someone who rarely goes out");
    }

    /**
     * Someone who skipped the lifestyle question still gets a plan. The alternative - having
     * onboarding pick a tier for them - would put that policy in whichever caller needed one first.
     */
    @Test
    void aUserWhoNeverSaidHowOftenTheyGoOutGetsTheLeastWeProtectForAnyone() {
        DiscretionaryFloor floor = calculator.floorFor(
                FloorRequest.withoutALifestyleTier(Money.of(4_000), Money.of(1_800)));

        assertThat(floor.monthly())
                .describedAs("3% of income - the lower clamp, and nothing claimed beyond it")
                .isEqualTo(Money.of(120));
        assertThat(floor.monthly().isPositive()).isTrue();
        assertThat(floor.perCategory().values().stream().reduce(Money.ZERO, Money::plus))
                .isEqualTo(floor.monthly());
    }

    /**
     * And the basis must not claim a grounding it does not have. Saying "typical for someone who
     * goes out regularly" about a person who never said how often they go out would be inventing
     * the justification, which is worse than offering no figure.
     */
    @Test
    void aFloorWithNoTierDoesNotClaimToBeTypicalOfAnyone() {
        DiscretionaryFloor floor = calculator.floorFor(
                FloorRequest.withoutALifestyleTier(Money.of(4_000), Money.of(1_800)));

        assertThat(floor.basis()).doesNotContain("typical for someone");
        assertThat(floor.basis())
                .isEqualTo("the least we protect for anyone, until you tell us how often you go out");
    }

    /** Their own answer still wins outright, tier or no tier. */
    @Test
    void aUserWithNoTierWhoAnswersTheQuestionKeepsTheirOwnFigure() {
        DiscretionaryFloor floor = calculator.floorFor(
                FloorRequest.withoutALifestyleTier(Money.of(4_000), Money.of(1_800))
                        .statedBy(Money.of(300)));

        assertThat(floor.monthly()).isEqualTo(Money.of(300));
        assertThat(floor.userProvided()).isTrue();
    }

    /**
     * Never show an internal term in the interface. A person asked about their "discretionary
     * floor" is being asked to guess, and a person shown "DINING_OUT" is being shown a variable
     * name.
     */
    @Test
    void theFloorQuestionNeverShowsAnInternalTerm() {
        SpendingQuestion question = calculator
                .floorFor(FloorRequest.of(LifestyleTier.FREQUENT, Money.of(4_000), Money.of(1_800)))
                .asQuestion();

        String rendered = String.join(
                        " ", question.question(), question.why(), question.basis())
                + " " + String.join(" ", question.explainedCoverage());

        assertThat(rendered.toLowerCase())
                .doesNotContain("discretionary")
                .doesNotContain("floor")
                .doesNotContain("baseline")
                .doesNotContain("rigidity")
                .doesNotContain("quintile");
        for (SpendCategory category : SpendCategory.values()) {
            assertThat(rendered).doesNotContain(category.name());
        }
    }
}
