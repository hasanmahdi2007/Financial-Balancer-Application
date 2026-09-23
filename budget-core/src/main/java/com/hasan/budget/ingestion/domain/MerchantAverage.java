package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.Money;
import java.util.Objects;

/**
 * What one merchant actually costs this user, averaged over their own purchases.
 *
 * <p>The output of grouping by the provider's stable merchant id - nothing more, and deliberately
 * so. Asked what a restaurant costs, a language model returns a confident, plausible, unverifiable
 * number feeding the same arithmetic; the user's own receipts are neither plausible nor
 * unverifiable, they are what happened.
 *
 * <p>Kept as this module's own shape rather than the planning module's, so the seam stays one
 * direction: whoever needs it maps it across, and Plaid's idea of a merchant never reaches the
 * engine.
 *
 * @param sampleSize how many purchases the average is over, so one unusual visit does not outweigh a
 *     dozen at the regular place
 */
public record MerchantAverage(
        String merchantEntityId, String merchantName, Money averageTicket, int sampleSize) {

    public MerchantAverage {
        Objects.requireNonNull(merchantEntityId, "merchantEntityId");
        Objects.requireNonNull(merchantName, "merchantName");
        Objects.requireNonNull(averageTicket, "averageTicket");
        if (sampleSize < 1) {
            throw new IllegalArgumentException("sampleSize must be >= 1 but was " + sampleSize);
        }
    }
}
