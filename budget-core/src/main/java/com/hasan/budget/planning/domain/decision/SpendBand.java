package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;

/**
 * How dear a meal is, as a multiple of a typical one in the user's own city.
 *
 * <p>This is the {@code meal_band} lookup table the design calls for, held as enum rows rather than
 * as dollar amounts anywhere in the code. A price in dollars would be wrong in Beirut the moment it
 * was right in San Francisco; a multiple of the local typical meal is right in both, from one table
 * instead of two branches. Adding a band is one row here and nothing else.
 *
 * <p>The multiple is a whole percentage rather than a decimal because a raw {@code double} or
 * {@code BigDecimal} field in the domain is exactly what {@code Money} exists to prevent, and an
 * ArchUnit rule enforces that.
 *
 * <p>Declaration order is ascending price, and {@link PriceLadder} relies on it to answer "what is
 * the next cheaper option" - the question that turns a warning into advice.
 */
public enum SpendBand {

    FAST_FOOD("Fast food", "a quick takeaway, street food, or a coffee and a pastry", 40),
    LOW("Cheap and cheerful", "a sandwich shop, a food court, or a casual local place", 60),
    MEDIUM("A normal meal out", "a regular restaurant or cafe lunch", 100),
    HIGH("Somewhere nicer", "a proper dinner out, with a drink", 180),
    FANCY("A special occasion", "fine dining, or a big night out", 320);

    private final String label;
    private final String covers;
    private final int percentOfTypical;

    SpendBand(String label, String covers, int percentOfTypical) {
        this.label = label;
        this.covers = covers;
        this.percentOfTypical = percentOfTypical;
    }

    /** What to call this band in the interface. Never show the constant name to a user. */
    public String label() {
        return label;
    }

    /** Plain-language examples, so the user picks a band by recognising it rather than guessing. */
    public String covers() {
        return covers;
    }

    /** This band's price as a whole percentage of a typical meal in the user's city. */
    public int percentOfTypical() {
        return percentOfTypical;
    }

    /** This band's price where a typical meal costs {@code typicalTicket}. */
    public Money priceFrom(Money typicalTicket) {
        return Amounts.percentOf(typicalTicket, percentOfTypical);
    }
}
