package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The priced options on offer, cheapest first.
 *
 * <p>It exists so the engine can answer "what would work instead", which is the difference between
 * advice and a scold. A warning on its own tells the user something they already suspected; the next
 * rung down with its own price and its own verdict tells them what to do about it.
 *
 * <p>The ladder is built from the city's own dining baseline rather than from dollar constants, so
 * Beirut and San Francisco come out of one table. Where the user's transactions already say what
 * they pay, the observation replaces the estimate - the estimate only ever existed because there was
 * nothing better.
 */
public record PriceLadder(List<TicketEstimate> options) {

    /**
     * How many meals out a month a typical dining baseline is assumed to cover. About five a week,
     * counting coffees and takeaway, which is what the {@code DINING_OUT} category says it covers.
     *
     * <p>It is an assumption, and it is a single named one rather than a number buried in an
     * expression, because it is the only modelling judgement in this file and the figure a reviewer
     * should be able to find and argue with. Changing it moves every band price by the same
     * proportion and changes no relative comparison, which is why the band ratios can be tested
     * independently of whether this number is right.
     */
    public static final int TYPICAL_MEALS_OUT_PER_MONTH = 20;

    public PriceLadder {
        options = List.copyOf(options);
    }

    /** No options to offer, for a one-off purchase that came with no ladder of alternatives. */
    public static PriceLadder none() {
        return new PriceLadder(List.of());
    }

    /**
     * Every band priced against a city's monthly dining baseline, with observed merchant averages
     * replacing the estimate wherever the user's own transactions cover a band.
     *
     * <p>Because each band is a fixed percentage of the typical meal, and the typical meal is a fixed
     * fraction of the baseline, a city whose baseline is three times another's produces band prices
     * three times as high, from this one table.
     */
    public static PriceLadder fromDiningBaseline(Money monthlyDiningBaseline, List<ObservedTicket> observed) {
        Objects.requireNonNull(monthlyDiningBaseline, "monthlyDiningBaseline");
        return fromTypicalTicket(
                Amounts.perPeriodAtLeast(monthlyDiningBaseline, TYPICAL_MEALS_OUT_PER_MONTH), observed);
    }

    /**
     * Every band priced against what a typical meal costs, with observed merchant averages replacing
     * the estimate wherever the user's own transactions cover a band.
     */
    public static PriceLadder fromTypicalTicket(Money typicalTicket, List<ObservedTicket> observed) {
        Objects.requireNonNull(typicalTicket, "typicalTicket");
        Objects.requireNonNull(observed, "observed");

        Map<SpendBand, Money> estimated = new EnumMap<>(SpendBand.class);
        for (SpendBand band : SpendBand.values()) {
            estimated.put(band, band.priceFrom(typicalTicket));
        }
        Map<SpendBand, Money> observedByBand = averageByBand(observed, estimated);

        List<TicketEstimate> priced = new ArrayList<>(SpendBand.values().length);
        for (SpendBand band : SpendBand.values()) {
            Money seen = observedByBand.get(band);
            priced.add(seen == null
                    ? TicketEstimate.forBand(band, estimated.get(band), PriceBasis.ESTIMATED)
                    : TicketEstimate.forBand(band, seen, PriceBasis.OBSERVED));
        }
        return new PriceLadder(priced);
    }

    /** The priced option for one band, empty if this ladder does not carry it. */
    public Optional<TicketEstimate> forBand(SpendBand band) {
        return options.stream().filter(option -> option.band() == band).findFirst();
    }

    /**
     * The dearest option still strictly cheaper than the given price, which is the one worth
     * offering as an alternative. Empty when the user already picked the cheapest thing on the
     * ladder - there is then nothing cheaper to suggest, and inventing one would be a lie.
     */
    public Optional<TicketEstimate> nextCheaperThan(Money price) {
        Objects.requireNonNull(price, "price");
        return options.stream()
                .filter(option -> option.price().compareTo(price) < 0)
                .max(Comparator.comparing(TicketEstimate::price));
    }

    /**
     * Groups the user's merchants into bands and averages each group, weighted by how many
     * transactions each merchant contributed.
     *
     * <p>A merchant joins the band whose estimated price is nearest its average ticket, ties going to
     * the cheaper band. That is arithmetic over one number, not a classifier: the alternative -
     * asking something to judge which band a restaurant belongs to - reintroduces exactly the
     * unverifiable answer this whole path exists to avoid.
     */
    private static Map<SpendBand, Money> averageByBand(
            List<ObservedTicket> observed, Map<SpendBand, Money> estimated) {

        Map<SpendBand, Money> weightedTotal = new EnumMap<>(SpendBand.class);
        Map<SpendBand, Integer> sampleCount = new EnumMap<>(SpendBand.class);
        for (ObservedTicket ticket : observed) {
            SpendBand band = nearestBand(ticket.averageTicket(), estimated);
            weightedTotal.merge(band, ticket.averageTicket().times(ticket.sampleSize()), Money::plus);
            sampleCount.merge(band, ticket.sampleSize(), Integer::sum);
        }

        Map<SpendBand, Money> average = new EnumMap<>(SpendBand.class);
        for (Map.Entry<SpendBand, Money> entry : weightedTotal.entrySet()) {
            average.put(
                    entry.getKey(),
                    Amounts.perPeriodAtLeast(entry.getValue(), sampleCount.get(entry.getKey())));
        }
        return average;
    }

    private static SpendBand nearestBand(Money averageTicket, Map<SpendBand, Money> estimated) {
        SpendBand nearest = null;
        Money smallestGap = null;
        // Declaration order is ascending price, so the first band at a given distance is the cheaper
        // one and a tie resolves downward without a second comparison.
        for (SpendBand band : SpendBand.values()) {
            Money gap = distance(averageTicket, estimated.get(band));
            if (smallestGap == null || gap.compareTo(smallestGap) < 0) {
                nearest = band;
                smallestGap = gap;
            }
        }
        return nearest;
    }

    private static Money distance(Money left, Money right) {
        return left.compareTo(right) >= 0 ? left.minus(right) : right.minus(left);
    }
}
