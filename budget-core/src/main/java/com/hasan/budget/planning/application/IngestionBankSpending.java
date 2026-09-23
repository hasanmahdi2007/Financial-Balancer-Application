package com.hasan.budget.planning.application;

import com.hasan.budget.ingestion.application.IngestionService;
import com.hasan.budget.ingestion.domain.MerchantAverage;
import com.hasan.budget.ingestion.port.ObservedMerchantPrices;
import com.hasan.budget.planning.domain.decision.ObservedTicket;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link BankSpending}, answered from the ingestion module's ports.
 *
 * <p>This class is the whole of the join between bank data and the decision engine, and it is a
 * mapping and nothing else. The engine may not import {@code ingestion} - an ArchUnit rule says so,
 * and the reason is that a merchant as Plaid describes one has no business reaching arithmetic - so
 * the two modules keep their own shapes and this translates between them, in one direction.
 *
 * <p>{@code MerchantAverage} and {@code ObservedTicket} carry the same four values. That duplication
 * is deliberate on both sides and worth keeping: it is the price of neither module depending on the
 * other, and it costs one line here.
 */
final class IngestionBankSpending implements BankSpending {

    private final ObservedMerchantPrices prices;
    private final IngestionService ingestion;

    IngestionBankSpending(ObservedMerchantPrices prices, IngestionService ingestion) {
        this.prices = Objects.requireNonNull(prices, "prices");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
    }

    @Override
    public List<ObservedTicket> observedTickets(String userId, SpendCategory category, YearMonth month) {
        if (!PRICED_BY_THE_TICKET.contains(category)) {
            return List.of();
        }
        return prices.averagesFor(userId, category, month).stream()
                .map(IngestionBankSpending::asTicket)
                .toList();
    }

    /**
     * What has gone on this category this month, from the same summary the rest of the product reads.
     *
     * <p>Transfers are not spending there and are not spending here: paying a credit card settles
     * purchases that were already counted, and counting both would tell the user they have spent
     * twice what they have. That rule lives in the classification, once, which is exactly why this
     * asks for a summary rather than adding up transactions itself.
     */
    @Override
    public Optional<Money> spentThisMonth(String userId, SpendCategory category, YearMonth month) {
        var summary = ingestion.summaryFor(userId, month);
        return summary.transactionCount() == 0
                ? Optional.empty()
                : Optional.of(summary.spentOn(category));
    }

    /**
     * A finished month, as the bank recorded it: what each category cost, and what went into savings.
     *
     * <p>The same summary as above, read for a different purpose, so the two can never disagree about
     * what a month cost. An empty month comes back empty rather than as a row of zeroes: a user who
     * connected a bank last week has no August, and telling their plan that August cost nothing would
     * be the most damaging kind of wrong this product can be.
     */
    @Override
    public MeasuredMonth measuredMonth(String userId, YearMonth month) {
        var summary = ingestion.summaryFor(userId, month);
        if (summary.transactionCount() == 0) {
            return MeasuredMonth.none(month);
        }
        // Tax held back is worked out from the user's tax rate, never read off a statement, and the
        // planning inputs reject it outright - so it is dropped here rather than allowed to arrive as
        // a failure the user cannot act on if a provider ever tags a payment that way.
        Map<SpendCategory, Money> measured = summary.spendingByCategory().entrySet().stream()
                .filter(entry -> entry.getKey() != SpendCategory.TAX_RESERVE)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new MeasuredMonth(month, measured, summary.alreadySaving());
    }

    private static ObservedTicket asTicket(MerchantAverage average) {
        return new ObservedTicket(
                average.merchantEntityId(), average.merchantName(), average.averageTicket(), average.sampleSize());
    }
}
