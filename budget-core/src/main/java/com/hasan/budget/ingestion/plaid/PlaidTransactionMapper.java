package com.hasan.budget.ingestion.plaid;

import com.hasan.budget.ingestion.domain.AccountRole;
import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns one Plaid transaction into one of ours.
 *
 * <p>Three decisions live here, and each of them is a way of being wrong that produces no error.
 *
 * <p><strong>An unrecognised category is never dropped.</strong> It becomes ordinary spending in
 * "Everything else", increments a counter and is logged with the value that was missing. Dropping it
 * instead would remove real spending from the plan, and a plan that has lost spending reports a
 * larger surplus than the user has - the one direction of error they must never be shown. The
 * counter means the gap is visible in metrics rather than waiting to be noticed.
 *
 * <p><strong>The sign is left exactly as Plaid sends it.</strong> Positive is money out. Flipping it
 * here would look tidier and would quietly invert every figure in the product.
 *
 * <p><strong>The account decides what the category can mean.</strong> Money arriving on a credit
 * card or a loan is settling a debt, whatever it was tagged as.
 */
public class PlaidTransactionMapper {

    private static final Logger log = LoggerFactory.getLogger(PlaidTransactionMapper.class);
    private static final String UNMAPPED_METRIC = "ingestion.plaid.unmapped_category";
    private static final String NO_CATEGORY_AT_ALL = "(absent)";

    private final PfcMapping mapping;
    private final MeterRegistry meters;

    public PlaidTransactionMapper(PfcMapping mapping, MeterRegistry meters) {
        this.mapping = mapping;
        this.meters = meters;
    }

    NormalisedTransaction toTransaction(PlaidWire.Transaction source, Map<String, AccountRole> roles) {
        Money amount = new Money(source.amount());
        boolean moneyIn = amount.isNegative();
        AccountRole role = roles.getOrDefault(source.accountId(), AccountRole.CASH);
        Classification classification = role.settle(classify(source), moneyIn);

        return new NormalisedTransaction(
                source.transactionId(),
                source.accountId(),
                whenItHappened(source),
                amount,
                source.merchantName(),
                source.merchantEntityId(),
                source.location() == null ? null : source.location().lat(),
                source.location() == null ? null : source.location().lon(),
                classification,
                source.pending());
    }

    /**
     * When the user spent the money, rather than when the bank finished moving it. A purchase on the
     * 31st that posts on the 2nd belongs to the month the user made it, which is the month they will
     * be looking at when they ask where their money went.
     */
    private static LocalDate whenItHappened(PlaidWire.Transaction source) {
        return source.authorizedDate() != null ? source.authorizedDate() : source.date();
    }

    private Classification classify(PlaidWire.Transaction source) {
        String detailed = source.personalFinanceCategory() == null
                ? null
                : source.personalFinanceCategory().detailed();
        if (detailed != null) {
            var known = mapping.classify(detailed);
            if (known.isPresent()) {
                return known.get();
            }
        }
        return unmapped(detailed == null ? NO_CATEGORY_AT_ALL : detailed, source.transactionId());
    }

    private Classification unmapped(String detailed, String transactionId) {
        meters.counter(UNMAPPED_METRIC, "category", detailed).increment();
        log.warn(
                "Plaid category {} is not in the mapping table; counting transaction {} as {} so the "
                        + "spending is not lost. Add a row to pfc-mapping.csv.",
                detailed,
                transactionId,
                SpendCategory.OTHER.label());
        return Classification.spend(SpendCategory.OTHER);
    }
}
