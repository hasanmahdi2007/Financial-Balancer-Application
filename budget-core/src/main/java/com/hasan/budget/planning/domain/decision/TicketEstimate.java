package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * One thing the user could buy, priced, with its own wording and the basis for its price.
 *
 * <p>The decision engine is defined over this rather than over a meal band, which is what lets the
 * same function answer "can I afford a $200 jacket?" as answers the lunch question. A band is simply
 * the first way these get produced.
 *
 * @param band the meal band this came from, or null for a one-off purchase the user priced
 *     themselves. Carried so a caller can offer the ladder back as the same choices the user picked
 *     from, rather than having to match on the label text.
 * @param label what to call this in the interface
 * @param covers plain-language examples, so a person can tell which option is which without being
 *     shown a constant name or an internal term
 */
public record TicketEstimate(SpendBand band, String label, String covers, Money price, PriceBasis basis) {

    public TicketEstimate {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(covers, "covers");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(basis, "basis");
        if (price.isNegative()) {
            throw new IllegalArgumentException("price must not be negative but was " + price);
        }
    }

    /**
     * A band priced for this city. The wording comes from the band's own row, so a band renamed
     * there cannot leave a stale label here.
     */
    public static TicketEstimate forBand(SpendBand band, Money price, PriceBasis basis) {
        Objects.requireNonNull(band, "band");
        return new TicketEstimate(band, band.label(), band.covers(), price, basis);
    }

    /**
     * Something the user priced themselves - the $200 jacket. Its basis is their own figure, which
     * outranks anything we would have estimated.
     */
    public static TicketEstimate stated(String label, String covers, Money price) {
        return new TicketEstimate(null, label, covers, price, PriceBasis.USER_STATED);
    }
}
