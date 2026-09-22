package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * What the user actually pays at one merchant, averaged over their own transactions.
 *
 * <p>This is the output of a {@code GROUP BY merchant_entity_id}, nothing more. The merchant
 * identifier is stable, so grouping needs no string matching and no model: asking a language model
 * what a restaurant costs returns a confident, plausible, unverifiable number feeding the same
 * arithmetic, which is the failure mode already rejected for cost-of-living data.
 *
 * @param merchantEntityId the provider's stable merchant identifier, carried so the same merchant
 *     seen under two spellings of its name is still one group
 * @param sampleSize how many transactions the average is over. Used to weight merchants against each
 *     other, so one visit somewhere unusual does not outvote a dozen at the regular place.
 */
public record ObservedTicket(
        String merchantEntityId, String merchantName, Money averageTicket, int sampleSize) {

    public ObservedTicket {
        Objects.requireNonNull(merchantEntityId, "merchantEntityId");
        Objects.requireNonNull(merchantName, "merchantName");
        Objects.requireNonNull(averageTicket, "averageTicket");
        if (averageTicket.isNegative()) {
            throw new IllegalArgumentException("averageTicket must not be negative but was " + averageTicket);
        }
        if (sampleSize < 1) {
            throw new IllegalArgumentException("sampleSize must be >= 1 but was " + sampleSize);
        }
    }
}
