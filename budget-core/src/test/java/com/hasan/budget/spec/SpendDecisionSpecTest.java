package com.hasan.budget.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.planning.domain.decision.Adjustment;
import com.hasan.budget.planning.domain.decision.BudgetLine;
import com.hasan.budget.planning.domain.decision.CatchUpPlan;
import com.hasan.budget.planning.domain.decision.ObservedTicket;
import com.hasan.budget.planning.domain.decision.PriceBasis;
import com.hasan.budget.planning.domain.decision.PriceLadder;
import com.hasan.budget.planning.domain.decision.RebalanceOutcome;
import com.hasan.budget.planning.domain.decision.RebalanceRequest;
import com.hasan.budget.planning.domain.decision.RebalanceResult;
import com.hasan.budget.planning.domain.decision.Rebalancing;
import com.hasan.budget.planning.domain.decision.ResolutionOption;
import com.hasan.budget.planning.domain.decision.SavingHint;
import com.hasan.budget.planning.domain.decision.SavingLever;
import com.hasan.budget.planning.domain.decision.Share;
import com.hasan.budget.planning.domain.decision.SpendAssessment;
import com.hasan.budget.planning.domain.decision.SpendBand;
import com.hasan.budget.planning.domain.decision.SpendDecision;
import com.hasan.budget.planning.domain.decision.SpendDecisionRequest;
import com.hasan.budget.planning.domain.decision.SpendVerdict;
import com.hasan.budget.planning.domain.decision.TicketEstimate;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Executable specification for the second product surface (Stage 7): the pre-purchase affordability
 * check, and interactive rebalancing.
 *
 * <p>Both are the goal engine at a different horizon. The allocator answers "can I reach $3,000 by
 * March?"; these answer "can I afford $25 today?" and "what does more entertainment cost me?". All
 * three take a budget, subtract what is committed, spread the remainder over the periods left, and
 * compare — which is why none of this needs a model to decide anything.
 */
class SpendDecisionSpecTest {

    /**
     * The tenth of a thirty-day month, so twenty-one days remain counting today. A thirty-day month
     * rather than a thirty-one-day one because it makes every figure below divide out exactly, which
     * is what lets these cases assert equality rather than a tolerance.
     */
    private static final LocalDate THE_TENTH = LocalDate.of(2026, 6, 10);

    private static final LocalDate THE_LAST_DAY_OF_THE_MONTH = LocalDate.of(2026, 6, 30);

    private static final Money FOOD_ALLOWANCE = Money.of(600);
    private static final Money SPENT_BY_THE_TENTH = Money.of(200);

    /**
     * A typical meal in this user's city, from which every band price is derived. $25 for a normal
     * sit-down meal puts fast food at $10 and a cheap place at $15, which are the figures the worked
     * examples below use.
     */
    private static final Money TYPICAL_MEAL = Money.of(25);

    private static final List<ObservedTicket> NO_TRANSACTIONS_YET = List.of();

    /**
     * $600 allowance, $200 already spent, twenty-one days left ⇒ $400 over 21 days. Rounded down to
     * the cent, because a daily rate rounded up is one the month cannot actually sustain.
     */
    private static final Money SUSTAINABLE_DAILY = Money.of("19.04");

    @Nested
    @DisplayName("can I afford this meal")
    class Affordability {

        /**
         * Food allowance $600/mo, $200 spent by the 10th, 21 days left ⇒ about $19/day sustainable.
         * A $10 fast-food meal is COMFORTABLE.
         */
        @Test
        void aMealWellUnderTheSustainableDailyRateIsComfortable() {
            SpendAssessment assessment = decideOn(SpendBand.FAST_FOOD);

            assertThat(assessment.remainingDays()).isEqualTo(21);
            assertThat(assessment.remainingBudget()).isEqualTo(Money.of(400));
            assertThat(assessment.sustainableDaily()).isEqualTo(SUSTAINABLE_DAILY);
            assertThat(assessment.purchase().price()).isEqualTo(Money.of(10));
            assertThat(assessment.verdict()).isEqualTo(SpendVerdict.COMFORTABLE);

            // Nothing to make up afterwards, so no plan is offered. A catch-up plan attached to a meal
            // that needs none would read as a reprimand for spending inside the budget.
            assertThat(assessment.catchUp()).isEmpty();
        }

        /** The same situation with a $25 medium lunch is OVER_BUDGET, and says so plainly. */
        @Test
        void aMealAboveTheSustainableDailyRateIsOverBudget() {
            SpendAssessment assessment = decideOn(SpendBand.MEDIUM);

            assertThat(assessment.purchase().price()).isEqualTo(Money.of(25));
            assertThat(assessment.verdict()).isEqualTo(SpendVerdict.OVER_BUDGET);

            // Plainly, and in the user's own language: nothing here shows a constant name or asks them
            // to know what a sustainable daily rate is.
            assertThat(assessment.verdict().label()).isEqualTo("This is more than you have room for");
            assertThat(assessment.verdict().meaning()).doesNotContain("OVER_BUDGET", "sustainable");
        }

        /**
         * A warning alone is a scold. The verdict carries the next band down with its own estimate
         * and its own verdict, so the user is told what would work, not only what would not.
         */
        @Test
        void theVerdictAlwaysOffersTheNextCheaperBandAndItsVerdict() {
            SpendAssessment medium = decideOn(SpendBand.MEDIUM);

            assertThat(medium.cheaper()).isPresent();
            assertThat(medium.cheaper().orElseThrow().estimate().band()).isEqualTo(SpendBand.LOW);
            assertThat(medium.cheaper().orElseThrow().estimate().price()).isEqualTo(Money.of(15));
            // The point of carrying the verdict: $15 is not merely cheaper, it is affordable.
            assertThat(medium.cheaper().orElseThrow().verdict()).isEqualTo(SpendVerdict.COMFORTABLE);

            // And the alternative is judged honestly rather than assumed to be fine. One rung down from
            // a $45 dinner is still $25, which is still over budget, and saying so is the whole reason
            // the verdict travels with the suggestion.
            SpendAssessment high = decideOn(SpendBand.HIGH);
            assertThat(high.cheaper().orElseThrow().estimate().band()).isEqualTo(SpendBand.MEDIUM);
            assertThat(high.cheaper().orElseThrow().verdict()).isEqualTo(SpendVerdict.OVER_BUDGET);

            // Nothing is cheaper than the cheapest. Inventing an option here would be a lie, so the
            // engine offers none.
            assertThat(decideOn(SpendBand.FAST_FOOD).cheaper()).isEmpty();
        }

        /**
         * The catch-up plan the user asked for: if the meal is taken anyway, the following days run
         * at a reduced rate, and following it lands exactly on the allowance rather than near it.
         */
        @Test
        void followingTheCatchUpPlanLandsExactlyOnTheAllowance() {
            SpendAssessment assessment = decideOn(SpendBand.MEDIUM);
            CatchUpPlan plan = assessment.catchUp().orElseThrow();

            assertThat(plan.fitsThisMonth()).isTrue();
            assertThat(plan.days()).isEqualTo(20);
            // $400 left, $25 spent on lunch, $375 over the twenty days that follow.
            assertThat(plan.reducedDailyRate()).isEqualTo(Money.of("18.75"));
            assertThat(plan.reductionPerDay()).isEqualTo(Money.of("0.29"));
            assertThat(plan.spillsIntoNextMonth()).isEqualTo(Money.ZERO);

            // Exactly, not nearly. The lunch plus every reduced day adds up to the money that was left,
            // and the month therefore ends on the allowance to the cent. A daily rate rounded the other
            // way would overshoot it by a cent a day, twenty times over, and still call itself a plan.
            assertThat(plan.totalIfFollowed(assessment.purchase().price()))
                    .isEqualTo(assessment.remainingBudget());
            assertThat(assessment.spentMonthToDate().plus(plan.totalIfFollowed(Money.of(25))))
                    .isEqualTo(FOOD_ALLOWANCE);
        }

        /** On the last day of the month there is one day left, not zero. No division by zero. */
        @Test
        void theLastDayOfTheMonthIsHandled() {
            SpendAssessment cheap = decide(request(bandLadder().forBand(SpendBand.FAST_FOOD).orElseThrow(),
                    FOOD_ALLOWANCE, SPENT_BY_THE_TENTH, THE_LAST_DAY_OF_THE_MONTH));

            assertThat(cheap.remainingDays()).isEqualTo(1);
            // One day, so the whole remainder is today's to spend rather than a twenty-first of it.
            assertThat(cheap.sustainableDaily()).isEqualTo(Money.of(400));
            assertThat(cheap.verdict()).isEqualTo(SpendVerdict.COMFORTABLE);

            // And the case that would divide by zero: something the last day cannot absorb, with no
            // following days to spread a reduction over. There is no plan, and the answer says so
            // instead of throwing or inventing one.
            SpendAssessment extravagant = decide(request(
                    TicketEstimate.stated("A farewell dinner", "the whole table, on you", Money.of(500)),
                    FOOD_ALLOWANCE,
                    SPENT_BY_THE_TENTH,
                    THE_LAST_DAY_OF_THE_MONTH));

            CatchUpPlan plan = extravagant.catchUp().orElseThrow();
            assertThat(plan.days()).isZero();
            assertThat(plan.fitsThisMonth()).isFalse();
            assertThat(plan.spillsIntoNextMonth()).isEqualTo(Money.of(100));
        }

        /**
         * If the allowance is already blown, every band is OVER_BUDGET and the catch-up necessarily
         * spans more than the requested horizon. Say that rather than inventing a plan that works.
         */
        @Test
        void anAlreadyExhaustedAllowanceReportsAnHonestlyLongerRecovery() {
            Money alreadyOverspent = Money.of(650);

            for (SpendBand band : SpendBand.values()) {
                SpendAssessment assessment = decide(request(
                        bandLadder().forBand(band).orElseThrow(), FOOD_ALLOWANCE, alreadyOverspent, THE_TENTH));

                // Not even fast food. There is nothing left per day, and pretending a cheap meal is
                // fine because it is cheap is how an app talks someone further into a hole.
                assertThat(assessment.sustainableDaily()).isEqualTo(Money.ZERO);
                assertThat(assessment.verdict()).isEqualTo(SpendVerdict.OVER_BUDGET);

                CatchUpPlan plan = assessment.catchUp().orElseThrow();
                assertThat(plan.fitsThisMonth()).isFalse();
                assertThat(plan.reducedDailyRate()).isEqualTo(Money.ZERO);
            }

            // The overspend is reported rather than clamped away, and the recovery is quantified: even
            // eating nothing for the rest of the month, a $25 lunch leaves the month $75 over.
            SpendAssessment lunch = decide(request(
                    bandLadder().forBand(SpendBand.MEDIUM).orElseThrow(),
                    FOOD_ALLOWANCE,
                    alreadyOverspent,
                    THE_TENTH));
            assertThat(lunch.remainingBudget()).isEqualTo(Money.of(-50));
            assertThat(lunch.catchUp().orElseThrow().spillsIntoNextMonth()).isEqualTo(Money.of(75));
        }
    }

    @Nested
    @DisplayName("where the band prices come from")
    class BandPricing {

        /**
         * Bands scale with the city, so Beirut and San Francisco work from one table rather than
         * two branches. A medium meal costs more in the dearer city, by the ratio of their
         * DINING_OUT baselines.
         */
        @Test
        void bandEstimatesScaleWithTheCitysDiningBaseline() {
            Money beirutBaseline = Money.of(150);
            Money sanFranciscoBaseline = Money.of(450);

            PriceLadder beirut = PriceLadder.fromDiningBaseline(beirutBaseline, NO_TRANSACTIONS_YET);
            PriceLadder sanFrancisco =
                    PriceLadder.fromDiningBaseline(sanFranciscoBaseline, NO_TRANSACTIONS_YET);

            assertThat(priceOf(beirut, SpendBand.MEDIUM)).isEqualTo(Money.of("7.50"));
            assertThat(priceOf(sanFrancisco, SpendBand.MEDIUM)).isEqualTo(Money.of("22.50"));
            assertThat(priceOf(sanFrancisco, SpendBand.MEDIUM))
                    .isGreaterThan(priceOf(beirut, SpendBand.MEDIUM));

            // Every band, not just the middle one, and stated as a ratio rather than as two lists of
            // dollars: whatever the cities cost, the dearer city's price for a band divided by the
            // cheaper city's is the ratio of their baselines. That is the property one table buys, and
            // it is what two branches would eventually break.
            for (SpendBand band : SpendBand.values()) {
                assertThat(priceOf(sanFrancisco, band).times(150))
                        .as("band %s scales with the city", band)
                        .isEqualTo(priceOf(beirut, band).times(450));
            }
        }

        /**
         * Once transactions exist, the estimate is replaced by the observed average ticket per
         * merchant. merchant_entity_id is stable, so grouping needs no string matching and no model.
         */
        @Test
        void observedMerchantAveragesReplaceTheEstimateOnceDataExists() {
            // Two places the user actually eats, both cheap, one visited far more often. Grouped by the
            // provider's stable merchant id, so the same shop under two spellings is still one merchant.
            List<ObservedTicket> observed = List.of(
                    new ObservedTicket("mrc_barbar", "Barbar", Money.of(12), 8),
                    new ObservedTicket("mrc_corner", "Corner Cafe", Money.of(9), 2));

            PriceLadder ladder = PriceLadder.fromTypicalTicket(TYPICAL_MEAL, observed);
            TicketEstimate fastFood = ladder.forBand(SpendBand.FAST_FOOD).orElseThrow();

            // Weighted by how often they went: (12 x 8 + 9 x 2) / 10. Not the plain mean of $10.50,
            // which would let one visit somewhere unusual outvote a dozen at the regular place.
            assertThat(fastFood.price()).isEqualTo(Money.of("11.40"));
            assertThat(fastFood.basis()).isEqualTo(PriceBasis.OBSERVED);

            // And only that band. A band the user's transactions say nothing about keeps its estimate
            // and keeps admitting that it is one.
            TicketEstimate low = ladder.forBand(SpendBand.LOW).orElseThrow();
            assertThat(low.price()).isEqualTo(Money.of(15));
            assertThat(low.basis()).isEqualTo(PriceBasis.ESTIMATED);
        }

        /** Before any transactions exist, the estimate is labelled ESTIMATED like every other guess. */
        @Test
        void coldStartEstimatesAreLabelledAsEstimates() {
            PriceLadder ladder = PriceLadder.fromTypicalTicket(TYPICAL_MEAL, NO_TRANSACTIONS_YET);

            assertThat(ladder.options())
                    .hasSize(SpendBand.values().length)
                    .allSatisfy(option -> assertThat(option.basis()).isEqualTo(PriceBasis.ESTIMATED));

            // The label a person reads says so too, without the word ESTIMATED in it, and explains what
            // the figure is standing in for rather than leaving them to wonder.
            PriceBasis estimated = ladder.options().getFirst().basis();
            assertThat(estimated.label()).isEqualTo("Our estimate");
            assertThat(estimated.meaning()).contains("typical prices in your city");
        }
    }

    @Nested
    @DisplayName("rebalancing, with rigidity respected")
    class Rebalance {

        /** Raising one category by $50 takes exactly $50 from the others. The pool does not grow. */
        @Test
        void raisingOneCategoryTakesTheSameAmountFromOthers() {
            RebalanceRequest request = new RebalanceRequest(plan(), "eatingOut", Money.of(50));

            RebalanceResult result = Rebalancing.apply(request);

            assertThat(result.outcome()).isEqualTo(RebalanceOutcome.ABSORBED);
            assertThat(result.granted()).isEqualTo(Money.of(50));
            assertThat(result.residualGap()).isEqualTo(Money.ZERO);

            // Money moved; none was created. Asserted as the sum of every adjustment rather than by
            // reading the two lines that happened to move, so a third line quietly changing would fail
            // this too.
            assertThat(result.net()).isEqualTo(Money.ZERO);
            assertThat(totalAfter(request, result)).isEqualTo(request.total());
        }

        /** What the user called disposable gives before what they called essential. */
        @Test
        void disposableCategoriesGiveBeforeEssentialOnes() {
            RebalanceRequest request = new RebalanceRequest(plan(), "eatingOut", Money.of(150));

            RebalanceResult result = Rebalancing.apply(request);

            assertThat(result.outcome()).isEqualTo(RebalanceOutcome.ABSORBED);
            // Going out was "not very important", so it gives everything it has before clothes, which
            // were merely "flexible", are touched at all — and the streaming the user called "very
            // important" gives last and least.
            assertThat(donations(result))
                    .containsExactly(
                            entry("goingOut", Money.of(100)),
                            entry("clothes", Money.of(20)),
                            entry("streaming", Money.of(30)));
            assertThat(result.net()).isEqualTo(Money.ZERO);
        }

        /**
         * The gym-membership case. A locked item is never reduced to make the arithmetic work, and
         * never raised by rebalancing either.
         */
        @Test
        void aLockedItemIsNeverTouchedToBalanceTheBudget() {
            // The gym has $35 above its floor, which is exactly what the rebalance is short of. It is
            // still not taken, because the user said it cannot change.
            List<BudgetLine> lines = List.of(
                    locked("gym", "Your gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(60), Money.of(25)),
                    line("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(40), Money.of(30)),
                    line("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(260), Money.of(250)));

            RebalanceResult raisingSomethingElse =
                    Rebalancing.apply(new RebalanceRequest(lines, "eatingOut", Money.of(100)));

            assertThat(raisingSomethingElse.adjustmentFor("gym")).isEmpty();
            assertThat(raisingSomethingElse.outcome()).isEqualTo(RebalanceOutcome.PARTIALLY_ABSORBED);
            assertThat(raisingSomethingElse.granted()).isEqualTo(Money.of(40));
            assertThat(raisingSomethingElse.residualGap()).isEqualTo(Money.of(60));
            // Silence about a locked line would leave the user's second largest outflow unaddressed, so
            // it gets advice — just never a number.
            assertThat(raisingSomethingElse.hintFor("gym")).isPresent();

            // And the other direction, which is the half that is easy to forget. Asked to raise the very
            // thing they locked, the engine leaves it alone and says why.
            RebalanceResult raisingTheLockedLine =
                    Rebalancing.apply(new RebalanceRequest(lines, "gym", Money.of(20)));

            assertThat(raisingTheLockedLine.outcome()).isEqualTo(RebalanceOutcome.TARGET_IS_LOCKED);
            assertThat(raisingTheLockedLine.adjustments()).isEmpty();
            assertThat(raisingTheLockedLine.granted()).isEqualTo(Money.ZERO);
            assertThat(raisingTheLockedLine.hintFor("gym")).isPresent();
        }

        /** No category is pushed below its floor, which is its localBaseline. */
        @Test
        void noCategoryIsPushedBelowItsFloor() {
            List<BudgetLine> lines = List.of(
                    line("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(300), Money.of(250)),
                    line("clothes", "Clothes", SpendCategory.CLOTHING, Money.of(60), Money.of(60)),
                    line("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(50), Money.of(40)));

            RebalanceResult result = Rebalancing.apply(new RebalanceRequest(lines, "goingOut", Money.of(100)));

            // Eating out stops dead on its floor rather than a cent below it, and clothes, already at
            // theirs, are not asked for anything at all.
            assertThat(result.adjustmentFor("eatingOut").orElseThrow().to()).isEqualTo(Money.of(250));
            assertThat(result.adjustmentFor("clothes")).isEmpty();

            assertThat(result.outcome()).isEqualTo(RebalanceOutcome.PARTIALLY_ABSORBED);
            assertThat(result.granted()).isEqualTo(Money.of(50));
            assertThat(result.residualGap()).isEqualTo(Money.of(50));
            assertThat(result.net()).isEqualTo(Money.ZERO);
        }

        /**
         * When floors and locks mean the increase cannot be absorbed, report the two real options —
         * raise the considered share of the balance, or let a goal slip — rather than silently
         * producing an impossible budget.
         */
        @Test
        void anImpossibleIncreaseReportsTheTwoRealOptions() {
            List<BudgetLine> lines = List.of(
                    locked("rent", "Your rent", SpendCategory.RENT, Money.of(1200), Money.of(1200)),
                    locked("gym", "Your gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(60), Money.of(60)),
                    line("clothes", "Clothes", SpendCategory.CLOTHING, Money.of(40), Money.of(40)),
                    line("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(50), Money.of(50)));

            RebalanceResult result = Rebalancing.apply(new RebalanceRequest(lines, "goingOut", Money.of(50)));

            assertThat(result.outcome()).isEqualTo(RebalanceOutcome.INFEASIBLE);
            assertThat(result.adjustments()).isEmpty();
            assertThat(result.granted()).isEqualTo(Money.ZERO);
            assertThat(result.residualGap()).isEqualTo(Money.of(50));

            // Both options, because there genuinely are two and picking one for the user is a decision
            // that is not the engine's to make.
            assertThat(result.options())
                    .containsExactly(
                            ResolutionOption.COUNT_MORE_OF_YOUR_BALANCE,
                            ResolutionOption.GIVE_A_GOAL_MORE_TIME);
            // Offered in language a person can act on. "Raise the considered share" is what the code
            // calls it; it is not what the user is asked.
            assertThat(result.options())
                    .allSatisfy(option -> assertThat(option.label() + option.meaning())
                            .doesNotContain("considered share", "residual", "floor", "rigidity"));
        }

        /** The user types $700 or 10% and both land in the same stored number. */
        @Test
        void dollarsAndPercentagesAreTwoViewsOfOneValue() {
            Money pool = Money.of(7000);

            Share typedInDollars = Share.ofAmount(Money.of(700));
            Share movedASlider = Share.ofPercent(10, pool);

            assertThat(movedASlider).isEqualTo(typedInDollars);
            assertThat(movedASlider.amount()).isEqualTo(Money.of(700));

            // And it reads back where they left it, so the two views cannot drift: there is one stored
            // number and the percentage is a view of it.
            assertThat(typedInDollars.asPercentOf(pool)).isEqualTo(10);

            // Whichever way it was typed, the request downstream is the same request.
            List<BudgetLine> lines = plan();
            assertThat(RebalanceRequest.raise(lines, "eatingOut", movedASlider))
                    .isEqualTo(RebalanceRequest.raise(lines, "eatingOut", typedInDollars));
        }
    }

    @Nested
    @DisplayName("hints, for what the engine may not cut")
    class Hints {

        /** A locked item yields a qualitative hint, never a number that could enter the arithmetic. */
        @Test
        void aLockedItemProducesAHintRatherThanATradeoff() {
            List<BudgetLine> lines = List.of(
                    locked("gym", "Your gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(60), Money.of(25)),
                    line("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(80), Money.of(40)));

            RebalanceResult result = Rebalancing.apply(new RebalanceRequest(lines, "goingOut", Money.of(20)));

            assertThat(result.adjustmentFor("gym")).isEmpty();
            assertThat(result.hintFor("gym").orElseThrow().lever())
                    .contains("memberships")
                    .contains("20-30%");
            // It names the user's own thing, not the taxonomy. "Cut Subscriptions" is not advice.
            assertThat(result.hintFor("gym").orElseThrow().label()).isEqualTo("Your gym membership");

            // The structural guarantee behind "never a number that could enter the arithmetic": a hint
            // has nowhere to put one. A percentage inside an English sentence cannot be summed by
            // accident; a Money field eventually would be.
            assertThat(SavingHint.class.getRecordComponents())
                    .extracting(RecordComponent::getType)
                    .doesNotContain(Money.class);
        }

        /** Rent's hint is about roommates or renegotiation, because there is no monthly lever. */
        @Test
        void eachCategoryHasAHintAppropriateToItsRealLever() {
            assertThat(SavingLever.forCategory(SpendCategory.RENT).lever())
                    .contains("roommate")
                    .contains("lease");
            assertThat(SavingLever.forCategory(SpendCategory.UTILITIES).lever()).contains("off-peak");
            assertThat(SavingLever.forCategory(SpendCategory.DEBT_PAYMENT).lever()).contains("refinancing");

            // Every category, driven off the taxonomy rather than a hand-written list, so a category
            // added later cannot ship with a locked line that has nothing to say about it.
            for (SpendCategory category : SpendCategory.values()) {
                assertThat(SavingLever.forCategory(category))
                        .as("%s has a saving lever", category)
                        .isNotNull();
                assertThat(SavingLever.forCategory(category).lever())
                        .as("%s explains itself without naming a constant", category)
                        .isNotBlank()
                        .doesNotContain(category.name());
            }
        }

        /** Hints never alter any total. Enabling them must not move the surplus by a cent. */
        @Test
        void hintsNeverChangeAnyNumberInThePlan() {
            List<BudgetLine> withoutLockedLines = List.of(
                    line("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(300), Money.of(250)),
                    line("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(80), Money.of(40)));
            List<BudgetLine> withLockedLines = List.of(
                    line("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(300), Money.of(250)),
                    line("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(80), Money.of(40)),
                    locked("rent", "Your rent", SpendCategory.RENT, Money.of(1200), Money.of(1100)),
                    locked("gym", "Your gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(60), Money.of(25)));

            RebalanceResult quiet =
                    Rebalancing.apply(new RebalanceRequest(withoutLockedLines, "eatingOut", Money.of(120)));
            RebalanceResult hinted =
                    Rebalancing.apply(new RebalanceRequest(withLockedLines, "eatingOut", Money.of(120)));

            // Two locked lines carrying $135 above their floors between them, and not one cent of it
            // reaches any figure. Every number is identical; only the advice differs.
            assertThat(hinted.hints()).hasSize(2);
            assertThat(quiet.hints()).isEmpty();
            assertThat(hinted.outcome()).isEqualTo(quiet.outcome());
            assertThat(hinted.granted()).isEqualTo(quiet.granted());
            assertThat(hinted.residualGap()).isEqualTo(quiet.residualGap());
            assertThat(hinted.net()).isEqualTo(quiet.net());
            assertThat(hinted.adjustments()).isEqualTo(quiet.adjustments());
        }
    }

    /**
     * The generalisation that costs nothing: the category is already a parameter, so the same
     * function answers "can I afford a $200 jacket?" as answers the lunch question.
     */
    @Test
    void theSameDecisionWorksForAnyCategoryNotJustFood() {
        TicketEstimate jacket = TicketEstimate.stated("A winter jacket", "the one in the window", Money.of(200));

        SpendAssessment assessment = SpendDecision.decide(new SpendDecisionRequest(
                SpendCategory.CLOTHING,
                Money.of(200),
                Money.ZERO,
                jacket,
                // No ladder: a one-off purchase comes with no menu of cheaper versions of itself, and
                // the engine offers nothing rather than fabricating one.
                PriceLadder.none(),
                THE_TENTH));

        assertThat(assessment.category()).isEqualTo(SpendCategory.CLOTHING);
        assertThat(assessment.purchase().basis()).isEqualTo(PriceBasis.USER_STATED);
        assertThat(assessment.cheaper()).isEmpty();

        // Identical arithmetic, not a second implementation: $200 over 21 days is $9.52 a day, the
        // jacket is far above it, and the whole clothing allowance goes on one purchase — so the
        // reduced rate for the rest of the month is nothing at all.
        assertThat(assessment.sustainableDaily()).isEqualTo(Money.of("9.52"));
        assertThat(assessment.verdict()).isEqualTo(SpendVerdict.OVER_BUDGET);

        CatchUpPlan plan = assessment.catchUp().orElseThrow();
        assertThat(plan.fitsThisMonth()).isTrue();
        assertThat(plan.reducedDailyRate()).isEqualTo(Money.ZERO);
        assertThat(plan.totalIfFollowed(jacket.price())).isEqualTo(assessment.remainingBudget());
    }

    // --- helpers -------------------------------------------------------------------------------

    private static PriceLadder bandLadder() {
        return PriceLadder.fromTypicalTicket(TYPICAL_MEAL, NO_TRANSACTIONS_YET);
    }

    private static SpendAssessment decideOn(SpendBand band) {
        return decide(request(
                bandLadder().forBand(band).orElseThrow(), FOOD_ALLOWANCE, SPENT_BY_THE_TENTH, THE_TENTH));
    }

    private static SpendAssessment decide(SpendDecisionRequest request) {
        return SpendDecision.decide(request);
    }

    private static SpendDecisionRequest request(
            TicketEstimate purchase, Money allowance, Money spent, LocalDate asOf) {
        return new SpendDecisionRequest(
                SpendCategory.DINING_OUT, allowance, spent, purchase, bandLadder(), asOf);
    }

    private static Money priceOf(PriceLadder ladder, SpendBand band) {
        return ladder.forBand(band).orElseThrow().price();
    }

    /**
     * A plan with one line in each rigidity tier the engine may cut, so cut order is observable. The
     * amounts are chosen so each tier runs out exactly: going out can give $100, clothes $20, and
     * streaming $35.
     */
    private static List<BudgetLine> plan() {
        return List.of(
                line("streaming", "Streaming and apps", SpendCategory.SUBSCRIPTIONS, Money.of(60), Money.of(25)),
                line("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(300), Money.of(250)),
                line("clothes", "Clothes", SpendCategory.CLOTHING, Money.of(60), Money.of(40)),
                line("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(100), Money.of(80)));
    }

    private static BudgetLine line(
            String id, String label, SpendCategory category, Money amount, Money floor) {
        return BudgetLine.of(id, label, category, amount, floor);
    }

    private static BudgetLine locked(
            String id, String label, SpendCategory category, Money amount, Money floor) {
        return new BudgetLine(id, label, category, amount, floor, Rigidity.LOCKED);
    }

    /** What each line gave up, in the order it was asked, so cut order can be asserted directly. */
    private static List<Donation> donations(RebalanceResult result) {
        return result.adjustments().stream()
                .filter(adjustment -> adjustment.change().isNegative())
                .map(adjustment -> new Donation(
                        adjustment.lineItemId(), Money.ZERO.minus(adjustment.change())))
                .toList();
    }

    private static Donation entry(String lineItemId, Money given) {
        return new Donation(lineItemId, given);
    }

    private record Donation(String lineItemId, Money given) {}

    /** The plan's total once every adjustment is applied, which rebalancing must never change. */
    private static Money totalAfter(RebalanceRequest request, RebalanceResult result) {
        Money total = Money.ZERO;
        for (BudgetLine budgetLine : request.lines()) {
            Money amount = result.adjustmentFor(budgetLine.id())
                    .map(Adjustment::to)
                    .orElse(budgetLine.amount());
            total = total.plus(amount);
        }
        return total;
    }
}
