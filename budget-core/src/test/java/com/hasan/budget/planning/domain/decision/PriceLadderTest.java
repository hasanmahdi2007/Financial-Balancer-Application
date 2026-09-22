package com.hasan.budget.planning.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.shared.Money;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The band table's own invariants, and the two places where observed prices could quietly break the
 * thing they were meant to improve.
 */
class PriceLadderTest {

    private static final Money TYPICAL_MEAL = Money.of(25);
    private static final List<ObservedTicket> NO_TRANSACTIONS_YET = List.of();

    /**
     * The invariant "the next cheaper band" depends on. Declaration order is documented as ascending
     * price, and a band added out of order would make the alternative offered to a user the wrong one
     * without failing anything else.
     */
    @Test
    void theBandTableIsOrderedCheapestFirst() {
        assertThat(SpendBand.values())
                .extracting(SpendBand::percentOfTypical)
                .isSortedAccordingTo(Comparator.naturalOrder());

        assertThat(SpendBand.MEDIUM.percentOfTypical())
                .as("the middle band is the typical meal itself, which is what the others scale against")
                .isEqualTo(100);
    }

    /** Every band explains itself in a person's words, because a band is something a user picks. */
    @Test
    void everyBandCanBeShownToAUserWithoutNamingAConstant() {
        for (SpendBand band : SpendBand.values()) {
            assertThat(band.label()).isNotBlank().doesNotContain(band.name());
            assertThat(band.covers()).isNotBlank().doesNotContain(band.name());
        }
    }

    /** The documented assumption, asserted, because every band price is a multiple of it. */
    @Test
    void theTypicalMealIsTheDiningBaselineSpreadOverTheAssumedMealsOut() {
        assertThat(PriceLadder.TYPICAL_MEALS_OUT_PER_MONTH).isEqualTo(20);

        PriceLadder ladder = PriceLadder.fromDiningBaseline(Money.of(500), NO_TRANSACTIONS_YET);

        assertThat(ladder.forBand(SpendBand.MEDIUM).orElseThrow().price()).isEqualTo(Money.of(25));
        assertThat(ladder.forBand(SpendBand.FAST_FOOD).orElseThrow().price()).isEqualTo(Money.of(10));
        assertThat(ladder.forBand(SpendBand.FANCY).orElseThrow().price()).isEqualTo(Money.of(80));
    }

    /**
     * The rounding happens once, at the end, and a band price from a monthly budget proves it.
     *
     * <p>$183.33 a month over twenty meals is $9.1665 a meal, which as a figure a person can be shown
     * is $9.17. Take the fancy band's 320% of <em>that</em> and you get $29.34; take 320% of the
     * budget itself in a single division and you get $29.33. The second is the honest one, and it is
     * what this returns — the intermediate meal price is never rounded, because it is never reported.
     *
     * <p>A cent, on one band, from one awkward baseline. It is worth a test precisely because it is
     * small: nothing else in the suite would have noticed, and the same compounding applied to a
     * larger chain is how figures start disagreeing with each other for no traceable reason.
     */
    @Test
    void aBandPriceRoundsOnceFromTheBudgetRatherThanTwiceViaATypicalMeal() {
        Money awkwardBaseline = Money.of("183.33");

        PriceLadder ladder = PriceLadder.fromDiningBaseline(awkwardBaseline, NO_TRANSACTIONS_YET);

        assertThat(ladder.forBand(SpendBand.FANCY).orElseThrow().price()).isEqualTo(Money.of("29.33"));
        // The route not taken, asserted so the difference is visible rather than asserted about.
        assertThat(SpendBand.FANCY.priceFrom(Money.of("9.17"))).isEqualTo(Money.of("29.34"));

        // The bands where the two routes happen to agree still have to come out right.
        assertThat(ladder.forBand(SpendBand.FAST_FOOD).orElseThrow().price()).isEqualTo(Money.of("3.67"));
        assertThat(ladder.forBand(SpendBand.MEDIUM).orElseThrow().price()).isEqualTo(Money.of("9.17"));
    }

    /**
     * Real prices replacing three of the five bands, and the ladder still climbs. That is not luck: a
     * merchant joins the band whose estimate is nearest its average, so each band only ever absorbs
     * prices from its own stretch of the range and cannot overtake its neighbour.
     *
     * <p>Worth asserting rather than assuming, because "the next cheaper band" is offered to the user
     * as advice, and a ladder that had quietly stopped ascending would offer them something dearer
     * while calling it cheaper. The lookup is by price rather than by position for the same reason.
     */
    @Test
    void observedPricesReplaceEstimatesWithoutReorderingTheLadder() {
        List<ObservedTicket> whereTheyActuallyEat = List.of(
                new ObservedTicket("mrc_barbar", "Barbar", Money.of(12), 8),
                new ObservedTicket("mrc_bistro", "The bistro downstairs", Money.of(18), 3),
                new ObservedTicket("mrc_rooftop", "The rooftop place", Money.of(40), 2));

        PriceLadder ladder = PriceLadder.fromTypicalTicket(TYPICAL_MEAL, whereTheyActuallyEat);

        assertThat(ladder.options())
                .extracting(TicketEstimate::price)
                .containsExactly(
                        Money.of(12), Money.of(18), Money.of(25), Money.of(40), Money.of(80))
                .isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(ladder.forBand(SpendBand.MEDIUM).orElseThrow().basis())
                .as("a band they have never been to keeps its estimate")
                .isEqualTo(PriceBasis.ESTIMATED);

        assertThat(ladder.nextCheaperThan(Money.of(25)).orElseThrow().band()).isEqualTo(SpendBand.LOW);
        // And nothing is offered below the cheapest price on the ladder.
        assertThat(ladder.nextCheaperThan(Money.of(12))).isEmpty();
    }

    /**
     * Weighting by how often each place was visited, which is what stops one meal somewhere unusual
     * from redefining what the user normally pays.
     */
    @Test
    void oneUnusualVisitDoesNotOutvoteTheRegularPlace() {
        List<ObservedTicket> observed = List.of(
                new ObservedTicket("mrc_regular", "The usual place", Money.of(10), 19),
                new ObservedTicket("mrc_treat", "Somewhere nicer, once", Money.of(30), 1));

        PriceLadder ladder = PriceLadder.fromTypicalTicket(TYPICAL_MEAL, observed);

        // The $30 visit lands in its own band rather than dragging the cheap one up, and the cheap band
        // stays at what they actually pay nineteen times out of twenty.
        assertThat(ladder.forBand(SpendBand.FAST_FOOD).orElseThrow().price()).isEqualTo(Money.of(10));
        assertThat(ladder.forBand(SpendBand.MEDIUM).orElseThrow().price()).isEqualTo(Money.of(30));
        assertThat(ladder.forBand(SpendBand.HIGH).orElseThrow().basis()).isEqualTo(PriceBasis.ESTIMATED);
    }

    @Test
    void anObservationWithNoTransactionsBehindItIsRejected() {
        assertThatThrownBy(() -> new ObservedTicket("mrc_x", "Somewhere", Money.of(10), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleSize");
    }

    /** A one-off purchase brings no ladder, and the engine offers nothing rather than inventing one. */
    @Test
    void anEmptyLadderOffersNothing() {
        assertThat(PriceLadder.none().options()).isEmpty();
        assertThat(PriceLadder.none().nextCheaperThan(Money.of(200))).isEmpty();
        assertThat(PriceLadder.none().forBand(SpendBand.MEDIUM)).isEmpty();
    }
}
