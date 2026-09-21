package com.hasan.budget.spec;

import static com.hasan.budget.planning.domain.surplus.SurplusCalculation.compute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.planning.domain.AllocationRequest;
import com.hasan.budget.planning.domain.AllocationResult;
import com.hasan.budget.planning.domain.AllocationStatus;
import com.hasan.budget.planning.domain.DiscretionarySpend;
import com.hasan.budget.planning.domain.GoalAllocation;
import com.hasan.budget.planning.domain.GoalInput;
import com.hasan.budget.planning.domain.GreedyPriorityAllocator;
import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.planning.domain.Tradeoff;
import com.hasan.budget.planning.domain.surplus.CategoryLine;
import com.hasan.budget.planning.domain.surplus.CategoryObservation;
import com.hasan.budget.planning.domain.surplus.ItemScope;
import com.hasan.budget.planning.domain.surplus.SurplusBreakdown;
import com.hasan.budget.planning.domain.surplus.SurplusInput;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Executable specification for the surplus formula (Stage 1).
 *
 * <p>Written before the code so the requirement is fixed in advance rather than reverse-engineered
 * from whatever gets built. Each case carries the worked numbers, so implementing it is filling in
 * a body and deleting the {@code @Disabled}.
 *
 * <p>The formula under specification:
 *
 * <pre>
 * surplus = income
 *         − fixedCommitments                 (taken as-is, never capped)
 *         − Σ min(actual, localBaseline)     (flexible essentials only)
 *         − Σ userLineItems                  (the user's own named commitments)
 *         − discretionaryFloor               (quality-of-life minimum)
 * </pre>
 *
 * <p>Every case runs with zero I/O, no container and no clock, which is the whole reason the
 * calculation was kept pure.
 */
class SurplusSpecTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 15);

    /**
     * A realistic quality-of-life minimum, and deliberately never zero: a zero floor is what makes
     * the engine recommend cutting all entertainment to nothing, which is advice nobody follows.
     * Deriving the real figure from lifestyle tier and obligation load belongs to P4; the formula
     * only ever receives it.
     */
    private static final Money FLOOR = Money.of(300);

    private static final Money SAVES_NOTHING_YET = Money.ZERO;

    @Nested
    @DisplayName("fixed commitments are never capped")
    class FixedCommitments {

        /**
         * The bug that nearly shipped. A cost-of-living index says what things COST, not what you
         * SHOULD spend; capping rent at the baseline pretends the user can pay less than their
         * lease this month, which overstates the surplus by the difference.
         *
         * <p>Rent actual $2,400, San Francisco baseline $2,100, income $6,000.
         * Expected: $2,400 is subtracted, not $2,100. Surplus reflects the real $2,400 outflow.
         */
        @Test
        void rentAboveTheLocalBaselineIsStillSubtractedInFull() {
            SurplusBreakdown breakdown =
                    compute(input("6000", List.of(spent(SpendCategory.RENT, "2400", "2100"))));

            assertThat(countedFor(breakdown, SpendCategory.RENT)).isEqualTo(Money.of(2400));
            assertThat(breakdown.fixedTotal()).isEqualTo(Money.of(2400));
            assertThat(breakdown.surplus()).isEqualTo(Money.of(3300));

            // The $300 excess is still reported, because "your rent is $300 above the local
            // baseline" is the sentence that explains where the goal went. It is simply not
            // allowed to change the arithmetic.
            assertThat(lineFor(breakdown, SpendCategory.RENT).isAboveBaseline()).isTrue();
        }

        /**
         * The inverse, and just as important: a cheap lease must not be inflated up to the
         * baseline. Rent actual $900, baseline $2,100 ⇒ $900 subtracted.
         */
        @Test
        void rentBelowTheLocalBaselineIsSubtractedAtWhatIsActuallyPaid() {
            SurplusBreakdown breakdown =
                    compute(input("6000", List.of(spent(SpendCategory.RENT, "900", "2100"))));

            assertThat(countedFor(breakdown, SpendCategory.RENT)).isEqualTo(Money.of(900));
            assertThat(breakdown.surplus()).isEqualTo(Money.of(4800));
            assertThat(lineFor(breakdown, SpendCategory.RENT).isAboveBaseline()).isFalse();
        }

        /**
         * DEBT_PAYMENT, HEALTHCARE and SUBSCRIPTIONS follow the same rule as RENT. Driven off the
         * taxonomy rather than a hand-written list, so a category added later is covered the moment
         * its row exists.
         */
        @Test
        void everyTakeAsIsCategoryIsSubtractedAtItsActualAmount() {
            for (SpendCategory category : categoriesUnder(BaselinePolicy.TAKE_AS_IS)) {
                SurplusBreakdown breakdown =
                        compute(input("6000", List.of(spent(category, "500", "200"))));

                assertThat(countedFor(breakdown, category))
                        .as("%s is subtracted at its actual amount", category)
                        .isEqualTo(Money.of(500));
                assertThat(breakdown.fixedTotal()).isEqualTo(Money.of(500));
            }
        }
    }

    @Nested
    @DisplayName("flexible essentials are capped at the local baseline")
    class FlexibleEssentials {

        /**
         * Groceries actual $700, baseline $450 ⇒ $450 subtracted. The $250 above baseline is
         * treated as controllable, which is what lets the plan say "this is where your goal went".
         */
        @Test
        void spendingAboveBaselineIsCappedSoTheExcessCountsAsControllable() {
            SurplusBreakdown breakdown =
                    compute(input("4000", List.of(spent(SpendCategory.GROCERIES, "700", "450"))));

            assertThat(countedFor(breakdown, SpendCategory.GROCERIES)).isEqualTo(Money.of(450));
            assertThat(breakdown.cappedTotal()).isEqualTo(Money.of(450));
            assertThat(lineFor(breakdown, SpendCategory.GROCERIES).isAboveBaseline()).isTrue();
        }

        /** Groceries actual $300, baseline $450 ⇒ $300 subtracted. Never inflate to the baseline. */
        @Test
        void spendingBelowBaselineUsesTheActualAmount() {
            SurplusBreakdown breakdown =
                    compute(input("4000", List.of(spent(SpendCategory.GROCERIES, "300", "450"))));

            assertThat(countedFor(breakdown, SpendCategory.GROCERIES)).isEqualTo(Money.of(300));
            assertThat(breakdown.cappedTotal()).isEqualTo(Money.of(300));
        }

        /**
         * Only CAP_AT_BASELINE categories are capped; nothing else consults the baseline at all.
         * Asserted by running each other category twice — once with a baseline well below what was
         * spent, once with none — and requiring the same answer both times.
         */
        @Test
        void noOtherCategoryConsultsTheBaseline() {
            for (SpendCategory category : SpendCategory.values()) {
                if (category.baselinePolicy() == BaselinePolicy.CAP_AT_BASELINE) {
                    continue;
                }
                SurplusBreakdown withBaseline =
                        compute(input("4000", List.of(spent(category, "500", "200"))));
                SurplusBreakdown withoutBaseline =
                        compute(input("4000", List.of(spent(category, "500"))));

                assertThat(withBaseline.surplus())
                        .as("%s must reach the same surplus with or without a baseline", category)
                        .isEqualTo(withoutBaseline.surplus());
            }
        }

        /**
         * The baseline is a default, not a rule. Where the user has stated what they actually spend
         * on groceries, that figure is what the cap uses — they know, and a researched average does
         * not. The choice itself is made in {@code costofliving}, where a user figure outranks even
         * an official one; by the time it arrives here it is simply the effective baseline, and the
         * arithmetic neither knows nor asks where it came from.
         *
         * <p>Actual $700 against a user figure of $800 ⇒ the whole $700 counts, where the local
         * $450 would have capped it.
         */
        @Test
        void aUserFigureAboveTheLocalBaselineRaisesTheCap() {
            SurplusBreakdown localOnly =
                    compute(input("4000", List.of(spent(SpendCategory.GROCERIES, "700", "450"))));
            SurplusBreakdown userSaysMore =
                    compute(input("4000", List.of(spent(SpendCategory.GROCERIES, "700", "800"))));

            assertThat(countedFor(localOnly, SpendCategory.GROCERIES)).isEqualTo(Money.of(450));
            assertThat(countedFor(userSaysMore, SpendCategory.GROCERIES)).isEqualTo(Money.of(700));
            assertThat(userSaysMore.surplus()).isEqualTo(localOnly.surplus().minus(Money.of(250)));
        }

        /**
         * The same override in the other direction, which matters just as much: a user who says
         * their groceries should be $400 gets a tighter cap than the local $450, and the extra $300
         * they actually spent is counted as controllable rather than unavoidable.
         */
        @Test
        void aUserFigureBelowTheLocalBaselineTightensTheCap() {
            SurplusBreakdown localOnly =
                    compute(input("4000", List.of(spent(SpendCategory.GROCERIES, "700", "450"))));
            SurplusBreakdown userSaysLess =
                    compute(input("4000", List.of(spent(SpendCategory.GROCERIES, "700", "400"))));

            assertThat(countedFor(userSaysLess, SpendCategory.GROCERIES)).isEqualTo(Money.of(400));
            assertThat(userSaysLess.surplus()).isEqualTo(localOnly.surplus().plus(Money.of(50)));
        }

        /**
         * A capped category with no figure to cap against. There is nothing to compare to, so the
         * observed amount stands: inventing a cap would understate real spending, and dropping the
         * category would lose it from the plan entirely.
         */
        @Test
        void aCappedCategoryWithNoBaselineIsSubtractedAtItsActualAmount() {
            SurplusBreakdown breakdown =
                    compute(input("4000", List.of(spent(SpendCategory.GROCERIES, "700"))));

            assertThat(countedFor(breakdown, SpendCategory.GROCERIES)).isEqualTo(Money.of(700));
            assertThat(breakdown.cappedTotal()).isEqualTo(Money.of(700));
        }
    }

    @Nested
    @DisplayName("transfers are not spending")
    class Transfers {

        /**
         * A $500 move into savings must not be subtracted. Counting it as spend understates the
         * surplus by $500 and then the goal it funds is counted separately: the same money twice.
         * It is reported as "you already save $500/mo" and flows into the surplus.
         */
        @Test
        void aTransferIntoSavingsIsReportedButNeverSubtracted() {
            List<CategoryObservation> spending = List.of(spent(SpendCategory.RENT, "1200"));

            SurplusBreakdown saving = compute(input("4000", spending, List.of(), Money.of(500)));
            SurplusBreakdown notSaving = compute(input("4000", spending, List.of(), Money.ZERO));

            assertThat(saving.alreadySaving()).isEqualTo(Money.of(500));
            assertThat(saving.surplus()).isEqualTo(notSaving.surplus());
            assertThat(saving.surplus()).isEqualTo(Money.of(2500));
        }

        /**
         * The sharper half of the same bug. $300 of groceries bought on a card, then a $300 card
         * payment: the groceries are counted once, at purchase. Counting the payment too would
         * subtract $600 for $300 of food.
         */
        @Test
        void payingOffACreditCardIsNotSpending() {
            SurplusBreakdown breakdown = monthOf(
                    "4000",
                    List.of(
                            purchase("tx-1", SpendCategory.GROCERIES, "300"),
                            movedBetweenOwnAccounts("tx-2", "300")),
                    Map.of(SpendCategory.GROCERIES, Money.of(450)));

            assertThat(breakdown.cappedTotal())
                    .as("$600 here would be the double count this taxonomy exists to prevent")
                    .isEqualTo(Money.of(300));
            assertThat(breakdown.alreadySaving()).isEqualTo(Money.of(300));
            assertThat(breakdown.surplus()).isEqualTo(Money.of(3400));
        }

        /**
         * Distinct from the above: a car loan instalment is a real outflow to a lender, not the
         * settlement of spending already counted, so DEBT_PAYMENT is subtracted in full.
         */
        @Test
        void aLoanInstalmentIsRealSpendingUnlikeACardPayment() {
            SurplusBreakdown loan = monthOf(
                    "4000", List.of(purchase("tx-1", SpendCategory.DEBT_PAYMENT, "450")), Map.of());
            SurplusBreakdown cardPayment =
                    monthOf("4000", List.of(movedBetweenOwnAccounts("tx-1", "450")), Map.of());

            assertThat(loan.fixedTotal()).isEqualTo(Money.of(450));
            assertThat(loan.surplus()).isEqualTo(Money.of(3250));

            // Same amount, same month, opposite treatment — which is the entire point of keeping
            // "kind of movement" off the "spend category" axis.
            assertThat(cardPayment.fixedTotal()).isEqualTo(Money.ZERO);
            assertThat(cardPayment.surplus()).isEqualTo(Money.of(3700));
        }
    }

    @Nested
    @DisplayName("the user's own named commitments")
    class UserLineItems {

        /** "Football stadium tickets, $300 a month" has to reach the arithmetic, not a note. */
        @Test
        void aCustomLineItemIsCountedInTheSurplus() {
            List<CategoryObservation> spending = List.of(spent(SpendCategory.RENT, "1200"));
            UserLineItem seasonTicket = UserLineItem.onTopOf(
                    "li-1", "Football season ticket", SpendCategory.SUBSCRIPTIONS, Money.of(300));

            SurplusBreakdown withItem =
                    compute(input("3000", spending, List.of(seasonTicket), SAVES_NOTHING_YET));
            SurplusBreakdown withoutItem =
                    compute(input("3000", spending, List.of(), SAVES_NOTHING_YET));

            assertThat(withItem.lineItemTotal()).isEqualTo(Money.of(300));
            assertThat(withItem.surplus()).isEqualTo(withoutItem.surplus().minus(Money.of(300)));
            assertThat(withItem.surplus()).isEqualTo(Money.of(1200));
        }

        /**
         * The reason a custom item needs no arm of its own: the parent category already picks one.
         * The identical $300 is taken as-is under SUBSCRIPTIONS and covered by the discretionary
         * floor under ENTERTAINMENT, and {@code SurplusCalculation} was not touched to make either
         * work.
         */
        @Test
        void aCustomItemIsTreatedByItsParentCategorysFormulaArm() {
            SurplusBreakdown asSubscription = compute(input(
                    "3000",
                    List.of(),
                    List.of(UserLineItem.onTopOf("li-1", "Match tickets", SpendCategory.SUBSCRIPTIONS, Money.of(300))),
                    SAVES_NOTHING_YET));
            SurplusBreakdown asEntertainment = compute(input(
                    "3000",
                    List.of(),
                    List.of(UserLineItem.onTopOf("li-1", "Match tickets", SpendCategory.ENTERTAINMENT, Money.of(300))),
                    SAVES_NOTHING_YET));

            assertThat(asSubscription.lineItemTotal()).isEqualTo(Money.of(300));
            assertThat(asEntertainment.lineItemTotal()).isEqualTo(Money.ZERO);
            assertThat(asEntertainment.surplus())
                    .isEqualTo(asSubscription.surplus().plus(Money.of(300)));
        }

        /** So advice can say "cut your gym by $20" rather than "cut Subscriptions by $20". */
        @Test
        void aCustomItemIsNamedInTheBreakdownRatherThanFoldedIntoItsCategory() {
            SurplusBreakdown breakdown = compute(input(
                    "3000",
                    List.of(spent(SpendCategory.SUBSCRIPTIONS, "60")),
                    List.of(UserLineItem.onTopOf("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45))),
                    SAVES_NOTHING_YET));

            CategoryLine gym = lineFor(breakdown, "li-gym");
            assertThat(gym.label()).isEqualTo("Gym membership");
            assertThat(gym.category()).isEqualTo(SpendCategory.SUBSCRIPTIONS);
            assertThat(gym.counted()).isEqualTo(Money.of(45));

            // The category's own streaming spend stays its own line rather than absorbing the gym.
            assertThat(countedFor(breakdown, SpendCategory.SUBSCRIPTIONS)).isEqualTo(Money.of(60));
            assertThat(breakdown.fixedTotal()).isEqualTo(Money.of(60));
            assertThat(breakdown.lineItemTotal()).isEqualTo(Money.of(45));
        }

        /**
         * Rigidity is the user's axis, so it defaults from the category and is theirs to override.
         * A gym membership is disposable for most people and untouchable for someone who trains
         * daily, and only they can say which.
         */
        @Test
        void aLineItemTakesItsParentsRigidityUnlessTheUserOverridesIt() {
            UserLineItem defaulted =
                    UserLineItem.onTopOf("li-gym", "Gym membership", SpendCategory.ENTERTAINMENT, Money.of(45));
            UserLineItem overridden = new UserLineItem(
                    "li-gym", "Gym membership", SpendCategory.ENTERTAINMENT, Money.of(45), Rigidity.LOCKED, ItemScope.ON_TOP);

            assertThat(defaulted.rigidity()).isEqualTo(SpendCategory.ENTERTAINMENT.defaultRigidity());
            assertThat(defaulted.rigidity()).isEqualTo(Rigidity.DISPOSABLE);
            assertThat(overridden.rigidity()).isEqualTo(Rigidity.LOCKED);
        }

        /**
         * Rigidity is carried, never acted on here: it decides what may be cut, which is a question
         * for whoever proposes cuts, not for the arithmetic. Two inputs differing only in rigidity
         * must therefore produce byte-identical breakdowns — if that ever stops being true, policy
         * has leaked into the formula.
         */
        @Test
        void theUsersRigidityNeverChangesTheArithmetic() {
            UserLineItem disposable = new UserLineItem(
                    "li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45), Rigidity.DISPOSABLE, ItemScope.ON_TOP);
            UserLineItem locked = new UserLineItem(
                    "li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45), Rigidity.LOCKED, ItemScope.ON_TOP);

            SurplusBreakdown asDisposable =
                    compute(input("3000", List.of(), List.of(disposable), SAVES_NOTHING_YET));
            SurplusBreakdown asLocked =
                    compute(input("3000", List.of(), List.of(locked), SAVES_NOTHING_YET));

            assertThat(asLocked).isEqualTo(asDisposable);
            assertThat(locked.rigidity()).isEqualTo(Rigidity.LOCKED);
        }

        /**
         * The other thing people do with a line item: put a name to part of what they already spend
         * rather than declare something new. A gym listed inside $60 of observed subscriptions is
         * already in that $60, so naming it must not move the surplus by a cent. Adding it would be
         * the credit-card double count arriving through a different door.
         */
        @Test
        void namingPartOfACategoryDoesNotAddToWhatIsSubtracted() {
            List<CategoryObservation> spending = List.of(spent(SpendCategory.SUBSCRIPTIONS, "60"));

            SurplusBreakdown named = compute(input(
                    "3000",
                    spending,
                    List.of(UserLineItem.alreadyIn("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45))),
                    SAVES_NOTHING_YET));
            SurplusBreakdown unnamed = compute(input("3000", spending, List.of(), SAVES_NOTHING_YET));

            assertThat(named.surplus()).isEqualTo(unnamed.surplus());
            assertThat(named.lineItemTotal()).isEqualTo(Money.ZERO);
            assertThat(named.fixedTotal()).isEqualTo(Money.of(60));
            assertThatConservationHolds(named);
        }

        /**
         * And it is still a line, because naming it is the entire reason it exists: the plan can say
         * "cut your gym by $20" where "cut Subscriptions by $20" tells the user nothing about what
         * to cancel.
         */
        @Test
        void aNamedPartOfACategoryIsStillReportedSoAdviceCanNameIt() {
            SurplusBreakdown breakdown = compute(input(
                    "3000",
                    List.of(spent(SpendCategory.SUBSCRIPTIONS, "60")),
                    List.of(UserLineItem.alreadyIn("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45))),
                    SAVES_NOTHING_YET));

            CategoryLine gym = lineFor(breakdown, "li-gym");
            assertThat(gym.label()).isEqualTo("Gym membership");
            assertThat(gym.actual()).isEqualTo(Money.of(45));
            assertThat(gym.counted()).isEqualTo(Money.ZERO);
        }

        /**
         * The same $45 gym, the same parent category, opposite arithmetic — which is exactly why the
         * user is asked rather than the answer inferred. Inferring it from whether an observation
         * happens to exist would silently change the number when their bank finally syncs.
         */
        @Test
        void theSameItemCountsOrNotAccordingToWhatTheUserSaidItWas() {
            List<CategoryObservation> spending = List.of(spent(SpendCategory.SUBSCRIPTIONS, "60"));

            SurplusBreakdown onTop = compute(input(
                    "3000",
                    spending,
                    List.of(UserLineItem.onTopOf("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45))),
                    SAVES_NOTHING_YET));
            SurplusBreakdown alreadyIn = compute(input(
                    "3000",
                    spending,
                    List.of(UserLineItem.alreadyIn("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45))),
                    SAVES_NOTHING_YET));

            assertThat(alreadyIn.surplus()).isEqualTo(onTop.surplus().plus(Money.of(45)));
        }

        /**
         * Naming $90 of gym inside $60 of observed subscriptions is a data error: the money is not
         * there to cut. Refused rather than clamped, because clamping would quietly discard whatever
         * the user actually meant.
         */
        @Test
        void namingMoreInsideACategoryThanItShowsIsRejected() {
            SurplusInput overNamed = input(
                    "3000",
                    List.of(spent(SpendCategory.SUBSCRIPTIONS, "60")),
                    List.of(UserLineItem.alreadyIn("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(90))),
                    SAVES_NOTHING_YET);

            assertThatThrownBy(() -> compute(overNamed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SUBSCRIPTIONS");
        }

        /** Two names inside one category have to fit together, not merely each on their own. */
        @Test
        void namedPartsOfOneCategoryAreCheckedAgainstTheirCombinedTotal() {
            SurplusInput overNamed = input(
                    "3000",
                    List.of(spent(SpendCategory.SUBSCRIPTIONS, "60")),
                    List.of(
                            UserLineItem.alreadyIn("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(40)),
                            UserLineItem.alreadyIn("li-tv", "Streaming", SpendCategory.SUBSCRIPTIONS, Money.of(40))),
                    SAVES_NOTHING_YET);

            assertThatThrownBy(() -> compute(overNamed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("80");
        }

        /**
         * Naming part of a category nothing was observed in is the same error seen earlier: there is
         * no total for it to be part of, and the user almost certainly meant it as a new commitment.
         */
        @Test
        void namingPartOfACategoryWithNothingObservedInItIsRejected() {
            SurplusInput orphaned = input(
                    "3000",
                    List.of(spent(SpendCategory.RENT, "1200")),
                    List.of(UserLineItem.alreadyIn("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45))),
                    SAVES_NOTHING_YET);

            assertThatThrownBy(() -> compute(orphaned))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Gym membership");
        }

        /**
         * The id is the handle a proposed cut uses to say "your gym" rather than "Subscriptions".
         * Two items sharing one makes that advice ambiguous about which line it meant, which defeats
         * the only reason these items carry names at all.
         */
        @Test
        void twoItemsCannotShareOneId() {
            SurplusInput clashing = input(
                    "3000",
                    List.of(),
                    List.of(
                            UserLineItem.onTopOf("li-1", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45)),
                            UserLineItem.onTopOf("li-1", "Season ticket", SpendCategory.SUBSCRIPTIONS, Money.of(300))),
                    SAVES_NOTHING_YET);

            assertThatThrownBy(() -> compute(clashing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("li-1");
        }

        /**
         * The question the user is asked is built from their own category's name, never from a
         * constant. "Part of what I already spend on Subscriptions" is answerable; "ALREADY_COUNTED"
         * is not, and neither is a sentence naming a category the interface does not show.
         */
        @Test
        void theChoiceIsPutToTheUserInTheirOwnCategorysWords() {
            for (SpendCategory category : SpendCategory.values()) {
                for (ItemScope scope : ItemScope.values()) {
                    assertThat(scope.labelFor(category))
                            .as("%s asked about %s", scope, category)
                            .contains(category.label())
                            .doesNotContain(category.name())
                            .doesNotContain(scope.name());
                    assertThat(scope.means()).isNotBlank();
                }
            }
        }
    }

    @Nested
    @DisplayName("arithmetic properties that must always hold")
    class Invariants {

        /**
         * Conservation. income == fixed + capped + floor + discretionary + surplus, exactly, with
         * no cent created or lost. This is the property most likely to catch a refactor.
         */
        @Test
        void everyCentOfIncomeIsAccountedForInTheBreakdown() {
            SurplusBreakdown breakdown = compute(input(
                    "5333.33",
                    List.of(
                            spent(SpendCategory.RENT, "1777.77", "1500"),
                            spent(SpendCategory.HEALTHCARE, "133.33"),
                            spent(SpendCategory.GROCERIES, "611.11", "455.55"),
                            spent(SpendCategory.UTILITIES, "188.88", "200"),
                            spent(SpendCategory.DINING_OUT, "244.44"))));

            assertThatConservationHolds(breakdown);
        }

        /** The same guarantee once the user's own named commitments are in the mix. */
        @Test
        void conservationStillHoldsWhenLineItemsArePresent() {
            SurplusBreakdown breakdown = compute(input(
                    "5333.33",
                    List.of(
                            spent(SpendCategory.RENT, "1777.77", "1500"),
                            spent(SpendCategory.GROCERIES, "611.11", "455.55"),
                            spent(SpendCategory.ENTERTAINMENT, "244.44")),
                    List.of(
                            UserLineItem.onTopOf("li-1", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of("44.44")),
                            UserLineItem.onTopOf("li-2", "Weekly food shop", SpendCategory.GROCERIES, Money.of("155.55")),
                            UserLineItem.onTopOf("li-3", "Match tickets", SpendCategory.ENTERTAINMENT, Money.of("77.77")),
                            UserLineItem.alreadyIn("li-4", "Corner shop", SpendCategory.GROCERIES, Money.of("222.22"))),
                    SAVES_NOTHING_YET));

            assertThatConservationHolds(breakdown);

            // The corner shop is a name put to part of the $611.11 already observed in groceries, so
            // it is absent from this total while the three genuine additions are in it.
            assertThat(breakdown.lineItemTotal()).isEqualTo(Money.of("199.99"));
        }

        /**
         * Essentials can exceed income, and the answer is a negative surplus, not zero. Clamping
         * to zero would hide the overspend and make the tradeoff engine understate the cuts needed.
         */
        @Test
        void surplusGoesNegativeWhenEssentialsExceedIncome() {
            SurplusBreakdown breakdown = compute(input(
                    "2000",
                    List.of(
                            spent(SpendCategory.RENT, "1800", "1400"),
                            spent(SpendCategory.HEALTHCARE, "400"),
                            spent(SpendCategory.GROCERIES, "300", "450"))));

            assertThat(breakdown.surplus()).isEqualTo(Money.of(-800));
            assertThat(breakdown.surplus().isNegative()).isTrue();
            assertThatConservationHolds(breakdown);
        }

        /** The discretionary floor is subtracted whole; it is a minimum, not a target. */
        @Test
        void theDiscretionaryFloorIsSubtractedInFull() {
            List<CategoryObservation> spending = List.of(
                    spent(SpendCategory.RENT, "1200"), spent(SpendCategory.DINING_OUT, "500"));

            SurplusBreakdown modest =
                    compute(input("4000", spending, List.of(), SAVES_NOTHING_YET, Money.of(300)));
            SurplusBreakdown generous =
                    compute(input("4000", spending, List.of(), SAVES_NOTHING_YET, Money.of(520)));

            assertThat(modest.discretionaryFloor()).isEqualTo(Money.of(300));
            assertThat(modest.surplus()).isEqualTo(Money.of(2500));

            // The whole $520 goes, not the part of it left over after the $500 actually spent on
            // eating out: the floor is what the user must be left with, not a budget to draw down.
            assertThat(generous.surplus()).isEqualTo(Money.of(2280));
        }

        /**
         * No clock. Two runs with identical inputs and the same asOf must produce identical output,
         * today and next year. ArchitectureTest enforces the mechanism; this asserts the result.
         */
        @Test
        void theSameInputsAlwaysProduceTheSameBreakdown() {
            SurplusInput request = input(
                    "6000",
                    List.of(
                            spent(SpendCategory.RENT, "2400", "2100"),
                            spent(SpendCategory.GROCERIES, "700", "450"),
                            spent(SpendCategory.ENTERTAINMENT, "240")),
                    List.of(UserLineItem.onTopOf("li-1", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(45))),
                    Money.of(400));

            assertThat(compute(request)).isEqualTo(compute(request));
        }

        /**
         * Two observations for one category would let the cap apply to each half separately, so
         * $700 of groceries split in two against a $450 baseline would count $700 rather than $450.
         * Only the caller knows whether two figures overlap, so this is refused rather than guessed.
         */
        @Test
        void aCategoryObservedTwiceIsRejectedRatherThanCappedTwice() {
            SurplusInput ambiguous = input(
                    "4000",
                    List.of(
                            spent(SpendCategory.GROCERIES, "350", "450"),
                            spent(SpendCategory.GROCERIES, "350", "450")));

            assertThatThrownBy(() -> compute(ambiguous))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GROCERIES");
        }
    }

    /**
     * The headline loop, end to end and with zero I/O: income and observed spending plus baselines
     * produce a surplus, the surplus plus goals produce a plan, and a goal that does not fit
     * produces a concrete tradeoff. If this passes, the product works.
     */
    @Test
    void incomeAndSpendingProduceAPlanWithConcreteTradeoffs() {
        // $6,000/mo in San Francisco: rent above the local baseline, groceries above it, a gym the
        // user named themselves, and two goals.
        SurplusBreakdown breakdown = compute(input(
                "6000",
                List.of(
                        spent(SpendCategory.RENT, "2400", "2100"),
                        spent(SpendCategory.HEALTHCARE, "320"),
                        spent(SpendCategory.SUBSCRIPTIONS, "60"),
                        spent(SpendCategory.UTILITIES, "180", "200"),
                        spent(SpendCategory.GROCERIES, "700", "450"),
                        spent(SpendCategory.TRANSPORT_FUEL, "160", "220"),
                        spent(SpendCategory.DINING_OUT, "380"),
                        spent(SpendCategory.ENTERTAINMENT, "240")),
                List.of(UserLineItem.onTopOf("li-gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(60))),
                Money.of(400)));

        // 2400 + 320 + 60 fixed, 180 + 450 + 160 capped, 60 of gym, 300 floor.
        assertThat(breakdown.fixedTotal()).isEqualTo(Money.of(2780));
        assertThat(breakdown.cappedTotal()).isEqualTo(Money.of(790));
        assertThat(breakdown.lineItemTotal()).isEqualTo(Money.of(60));
        assertThat(breakdown.surplus()).isEqualTo(Money.of(2070));
        assertThat(breakdown.alreadySaving()).isEqualTo(Money.of(400));
        assertThatConservationHolds(breakdown);

        AllocationResult plan = planFor(
                breakdown,
                List.of(
                        goal("emergency", "Emergency fund", "6000", "2027-01-15", Priority.CRITICAL),
                        goal("car", "Car", "10500", "2026-07-15", Priority.HIGH)));

        // The emergency fund outranks the car and is funded first; the car is left $180/mo short.
        assertThat(allocationOf(plan, "emergency").status()).isEqualTo(AllocationStatus.ON_TRACK);
        assertThat(allocationOf(plan, "emergency").allocated()).isEqualTo(Money.of(500));

        GoalAllocation car = allocationOf(plan, "car");
        assertThat(car.requiredMonthly()).isEqualTo(Money.of(1750));
        assertThat(car.allocated()).isEqualTo(Money.of(1570));
        assertThat(car.status()).isEqualTo(AllocationStatus.AT_RISK);
        assertThat(car.shortfall()).isEqualTo(Money.of(180));

        // And the tradeoff is a number the user can act on, taken from what they called disposable
        // before anything they called flexible or essential.
        assertThat(plan.tradeoffs())
                .containsExactly(new Tradeoff(SpendCategory.ENTERTAINMENT, Money.of(180)));
        assertThat(plan.residualGap()).isEqualTo(Money.ZERO);
        assertThat(plan.unallocatedSurplus()).isEqualTo(Money.ZERO);
    }

    /**
     * The other half of that promise. On $3,000 with this rent there is nothing left, and every cut
     * the engine is allowed to name covers $200 of a $1,000 gap. Listing those two cuts and
     * stopping would read as "do this and the goal is reachable", which is false; the residual gap
     * is what lets the caller offer the two honest options instead.
     */
    @Test
    void cutsThatCannotCloseTheGapAreReportedAsAResidualGap() {
        SurplusBreakdown breakdown = compute(input(
                "3000",
                List.of(
                        spent(SpendCategory.RENT, "1800", "1500"),
                        spent(SpendCategory.HEALTHCARE, "250"),
                        spent(SpendCategory.UTILITIES, "200", "200"),
                        spent(SpendCategory.GROCERIES, "600", "450"),
                        spent(SpendCategory.DINING_OUT, "120"),
                        spent(SpendCategory.ENTERTAINMENT, "80"))));

        assertThat(breakdown.surplus()).isEqualTo(Money.ZERO);

        AllocationResult plan = planFor(
                breakdown,
                List.of(goal("emergency", "Emergency fund", "6000", "2026-07-15", Priority.CRITICAL)));

        assertThat(allocationOf(plan, "emergency").status()).isEqualTo(AllocationStatus.INFEASIBLE);
        assertThat(plan.tradeoffs())
                .containsExactly(
                        new Tradeoff(SpendCategory.ENTERTAINMENT, Money.of(80)),
                        new Tradeoff(SpendCategory.DINING_OUT, Money.of(120)));
        assertThat(plan.residualGap()).isEqualTo(Money.of(800));
    }

    // --- building the inputs -------------------------------------------------------------------

    private static CategoryObservation spent(SpendCategory category, String actual, String baseline) {
        return new CategoryObservation(category, Money.of(actual), Money.of(baseline));
    }

    private static CategoryObservation spent(SpendCategory category, String actual) {
        return CategoryObservation.withoutBaseline(category, Money.of(actual));
    }

    private static SurplusInput input(String income, List<CategoryObservation> observations) {
        return input(income, observations, List.of(), SAVES_NOTHING_YET);
    }

    private static SurplusInput input(
            String income,
            List<CategoryObservation> observations,
            List<UserLineItem> lineItems,
            Money alreadySaving) {
        return input(income, observations, lineItems, alreadySaving, FLOOR);
    }

    private static SurplusInput input(
            String income,
            List<CategoryObservation> observations,
            List<UserLineItem> lineItems,
            Money alreadySaving,
            Money discretionaryFloor) {
        return new SurplusInput(
                Money.of(income), observations, lineItems, alreadySaving, discretionaryFloor, AS_OF);
    }

    private static NormalisedTransaction purchase(String externalId, SpendCategory category, String amount) {
        return transaction(externalId, amount, Classification.spend(category));
    }

    private static NormalisedTransaction movedBetweenOwnAccounts(String externalId, String amount) {
        return transaction(externalId, amount, Classification.notSpending(TransactionKind.TRANSFER_INTERNAL));
    }

    private static NormalisedTransaction transaction(
            String externalId, String amount, Classification classification) {
        // Positive is money leaving the account, matching the provider's own convention.
        return new NormalisedTransaction(
                externalId, "checking", AS_OF, Money.of(amount), null, null, null, null, classification, false);
    }

    /**
     * A month as the ingestion boundary hands it over. Only {@code kind == SPEND} becomes a
     * {@link CategoryObservation}; internal movement aggregates into {@code alreadySaving}, which is
     * reported and never subtracted. Deciding which provider label maps to which kind is P3's job —
     * what these cases assert is the consequence for the surplus once it has been got right.
     */
    private static SurplusBreakdown monthOf(
            String income, List<NormalisedTransaction> transactions, Map<SpendCategory, Money> baselines) {
        Map<SpendCategory, Money> spendByCategory = new EnumMap<>(SpendCategory.class);
        Money movedBetweenOwnAccounts = Money.ZERO;
        for (NormalisedTransaction transaction : transactions) {
            switch (transaction.classification().kind()) {
                case SPEND -> spendByCategory.merge(
                        transaction.classification().category(), transaction.amount(), Money::plus);
                case TRANSFER_INTERNAL -> movedBetweenOwnAccounts =
                        movedBetweenOwnAccounts.plus(transaction.amount());
                default -> throw new AssertionError(
                        "these cases are about spend and internal transfers only, not "
                                + transaction.classification().kind());
            }
        }

        List<CategoryObservation> observations = spendByCategory.entrySet().stream()
                .map(entry -> new CategoryObservation(entry.getKey(), entry.getValue(), baselines.get(entry.getKey())))
                .toList();
        return compute(input(income, observations, List.of(), movedBetweenOwnAccounts));
    }

    private static GoalInput goal(String id, String name, String target, String deadline, Priority priority) {
        return new GoalInput(
                id, name, Money.of(target), Money.ZERO, LocalDate.parse(deadline), priority);
    }

    /**
     * Hands the allocator a single {@code Money} and the spending it may propose reducing — never
     * the breakdown itself. An ArchUnit rule keeps that seam narrow in the production code; passing
     * anything wider here would make this test stop describing how the pieces really fit together.
     */
    private static AllocationResult planFor(SurplusBreakdown breakdown, List<GoalInput> goals) {
        List<DiscretionarySpend> cutCandidates = breakdown.lines().stream()
                .filter(line -> line.actual().isPositive())
                .map(line -> DiscretionarySpend.of(line.category(), line.actual()))
                .filter(DiscretionarySpend::isCuttable)
                .toList();

        return new GreedyPriorityAllocator()
                .allocate(new AllocationRequest(breakdown.surplus(), goals, cutCandidates, AS_OF));
    }

    // --- reading the results -------------------------------------------------------------------

    private static List<SpendCategory> categoriesUnder(BaselinePolicy policy) {
        return Arrays.stream(SpendCategory.values())
                .filter(category -> category.baselinePolicy() == policy)
                .toList();
    }

    private static CategoryLine lineFor(SurplusBreakdown breakdown, SpendCategory category) {
        return breakdown.lines().stream()
                .filter(line -> line.lineItemId() == null && line.category() == category)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + category));
    }

    private static CategoryLine lineFor(SurplusBreakdown breakdown, String lineItemId) {
        return breakdown.lines().stream()
                .filter(line -> lineItemId.equals(line.lineItemId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for item " + lineItemId));
    }

    private static Money countedFor(SurplusBreakdown breakdown, SpendCategory category) {
        return lineFor(breakdown, category).counted();
    }

    private static GoalAllocation allocationOf(AllocationResult plan, String goalId) {
        return plan.goalAllocations().stream()
                .filter(allocation -> allocation.goalId().equals(goalId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no allocation for " + goalId));
    }

    private static void assertThatConservationHolds(SurplusBreakdown breakdown) {
        Money accountedFor = breakdown.fixedTotal()
                .plus(breakdown.cappedTotal())
                .plus(breakdown.lineItemTotal())
                .plus(breakdown.discretionaryFloor())
                .plus(breakdown.surplus());

        assertThat(accountedFor)
                .as("every cent of income lands in exactly one part of the breakdown")
                .isEqualTo(breakdown.income());
    }
}
