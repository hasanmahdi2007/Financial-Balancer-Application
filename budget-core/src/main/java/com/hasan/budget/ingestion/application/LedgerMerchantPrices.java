package com.hasan.budget.ingestion.application;

import com.hasan.budget.ingestion.domain.MerchantAverage;
import com.hasan.budget.ingestion.port.ObservedMerchantPrices;
import com.hasan.budget.shared.SpendCategory;
import java.time.YearMonth;
import java.util.List;

/**
 * Answers what the user's regular merchants cost, from their own stored transactions.
 *
 * <p>Computed over the same ledger that produces the monthly summary rather than by a query of its
 * own. That is the point: a merchant average calculated separately would see raw classifications,
 * and would therefore disagree with the spending total on exactly the rows this module works hardest
 * to classify correctly - the payroll that looks like a restaurant, the card payment that looks like
 * salary. One reading of the data, used for both answers.
 *
 * <p>At one user and one month this is a few hundred rows, so it is grouped in memory rather than in
 * SQL. If a user ever has enough history for that to matter, the same grouping moves into the
 * repository behind this unchanged interface.
 */
public class LedgerMerchantPrices implements ObservedMerchantPrices {

    private final IngestionService ingestion;

    public LedgerMerchantPrices(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @Override
    public List<MerchantAverage> averagesFor(String userId, SpendCategory category, YearMonth month) {
        return ingestion.ledgerFor(userId).merchantAverages(category, month);
    }
}
