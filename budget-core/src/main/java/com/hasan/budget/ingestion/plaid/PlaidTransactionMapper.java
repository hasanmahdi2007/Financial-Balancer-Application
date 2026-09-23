package com.hasan.budget.ingestion.plaid;

import com.hasan.budget.ingestion.domain.AccountRole;
import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
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
 * <p><strong>An unrecognised category is never dropped, and what it becomes depends on which way the
 * money went.</strong> An unknown payment becomes ordinary spending in "Everything else", because
 * dropping it would remove real spending from the plan and report a larger surplus than the user
 * has. An unknown credit becomes an external movement instead: recorded as spending it would carry a
 * negative amount and <em>reduce</em> a category total, which flatters the plan in exactly the same
 * way, and calling it income would invent earnings out of money that might be borrowed. Either way
 * the counter increments and the value is logged, so the gap shows up in metrics rather than waiting
 * to be noticed.
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
        Classification classification = role.settle(classify(source, moneyIn), moneyIn);

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

    private Classification classify(PlaidWire.Transaction source, boolean moneyIn) {
        String detailed = source.personalFinanceCategory() == null
                ? null
                : source.personalFinanceCategory().detailed();
        if (detailed != null) {
            var known = mapping.classify(detailed);
            if (known.isPresent()) {
                return known.get();
            }
        }
        return unmapped(detailed == null ? NO_CATEGORY_AT_ALL : detailed, source.transactionId(), moneyIn);
    }

    /**
     * What to do with a category nobody has mapped, which depends on which way the money went.
     *
     * <p>Money out becomes ordinary spending, so it is still subtracted and the plan is never
     * flattered by a purchase it could not name.
     *
     * <p>Money in must <strong>not</strong> take the same route, and this is subtle enough to have
     * been wrong here first time round. An unrecognised credit recorded as spending carries a
     * negative amount, which <em>reduces</em> that category's total - so an unknown deposit would
     * quietly shrink someone's apparent spending and inflate their surplus, which is the one
     * direction of error this module exists to prevent. Nor can it be called income, since money
     * arriving unidentified is as likely to be borrowed. Recorded as an external movement, it
     * touches neither total, and the counter still says it needs a row.
     */
    private Classification unmapped(String detailed, String transactionId, boolean moneyIn) {
        meters.counter(UNMAPPED_METRIC, "category", detailed).increment();
        Classification fallback = moneyIn
                ? Classification.notSpending(TransactionKind.TRANSFER_EXTERNAL)
                : Classification.spend(SpendCategory.OTHER);
        log.warn(
                "Plaid category {} is not in the mapping table; transaction {} counted as {} so "
                        + "nothing is lost and nothing is invented. Add a row to pfc-mapping.csv.",
                detailed,
                transactionId,
                moneyIn ? "money in of an unknown kind" : SpendCategory.OTHER.label());
        return fallback;
    }
}
