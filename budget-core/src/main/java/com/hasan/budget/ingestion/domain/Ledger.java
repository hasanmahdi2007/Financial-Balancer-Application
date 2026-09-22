package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.Commitment;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything one user's connected banks have told us, and the few questions worth asking of it.
 *
 * <p>Pure, framework-free and clock-free: a month is passed in, never read, so the same stored rows
 * always produce the same answer. That is what lets the whole of this module's arithmetic be tested
 * in milliseconds against recorded responses.
 *
 * <p>Two passes happen once, at construction, and the order matters. First each transaction is
 * reconsidered in the light of any stream it belongs to, because repeating changes what a row means
 * - payroll from a restaurant stops being a refund, and a monthly entertainment charge becomes a
 * subscription. Only then are the two halves of each internal transfer collapsed, so that matching
 * is done on final classifications rather than provisional ones.
 *
 * <p>The transactions remain the single source of every amount. Streams contribute labels, cadence
 * and meaning, never money - if a stream's total were added to the spending its own transactions
 * already appear in, every subscription would be counted twice.
 */
public final class Ledger {

    private final List<LedgerEntry> entries;
    private final List<RecurringStream> streams;

    private Ledger(List<LedgerEntry> entries, List<RecurringStream> streams) {
        this.entries = entries;
        this.streams = streams;
    }

    public static Ledger of(List<LedgerEntry> entries, List<RecurringStream> streams) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(streams, "streams");
        return new Ledger(TransferPairs.collapse(inTheLightOfStreams(entries, streams)), List.copyOf(streams));
    }

    private static List<LedgerEntry> inTheLightOfStreams(
            List<LedgerEntry> entries, List<RecurringStream> streams) {
        Map<String, RecurringStream.Direction> memberships = new HashMap<>();
        for (RecurringStream stream : streams) {
            for (String memberId : stream.memberIds()) {
                memberships.put(memberId, stream.direction());
            }
        }
        if (memberships.isEmpty()) {
            return List.copyOf(entries);
        }
        List<LedgerEntry> revised = new ArrayList<>(entries.size());
        for (LedgerEntry entry : entries) {
            RecurringStream.Direction direction = memberships.get(entry.transaction().externalId());
            revised.add(direction == null
                    ? entry
                    : entry.reclassifiedAs(RecurringPolicy.inStream(entry.classification(), direction)));
        }
        return List.copyOf(revised);
    }

    /** Every stored transaction, after collapsing transfer pairs. */
    public List<LedgerEntry> entries() {
        return entries;
    }

    public List<LedgerEntry> entriesIn(YearMonth month) {
        return entries.stream()
                .filter(entry -> YearMonth.from(entry.transaction().date()).equals(month))
                .toList();
    }

    /**
     * What actually moved in one month.
     *
     * <p>Category totals are net of refunds and floored at zero. A month whose refunds exceed its
     * purchases in some category would otherwise report negative spending, which reads as income the
     * user never received. Flooring errs towards showing more spending rather than less, which is
     * the only direction of error that cannot flatter the plan.
     */
    public SpendingSummary summaryFor(YearMonth month) {
        Map<SpendCategory, Money> byCategory = new EnumMap<>(SpendCategory.class);
        Money income = Money.ZERO;
        Money fees = Money.ZERO;
        Money pending = Money.ZERO;
        List<LedgerEntry> inMonth = entriesIn(month);

        for (LedgerEntry entry : inMonth) {
            NormalisedTransaction transaction = entry.transaction();
            Money amount = transaction.amount();
            switch (transaction.classification().kind()) {
                case SPEND -> {
                    SpendCategory category = transaction.classification().category();
                    byCategory.merge(category, amount, Money::plus);
                    if (transaction.pending()) {
                        pending = pending.plus(amount);
                    }
                }
                case INCOME -> income = income.plus(SignConvention.shownToUser(amount));
                case FEE -> fees = fees.plus(amount);
                // Transfers between the user's own accounts are not spending and are never income.
                case TRANSFER_INTERNAL, TRANSFER_EXTERNAL, REFUND -> {}
            }
        }
        byCategory.replaceAll((category, total) -> atLeastNothing(total));
        byCategory.values().removeIf(Money::isZero);

        return new SpendingSummary(
                month,
                byCategory,
                atLeastNothing(income),
                atLeastNothing(fees),
                atLeastNothing(pending),
                inMonth.size());
    }

    /**
     * The repeating payments that are owed rather than chosen, named after the merchant.
     *
     * <p>Only streams whose own transactions say they are spending, and only categories that are
     * committed: a weekly grocery order repeats without being a commitment, because next week's
     * shop can be smaller and a lease cannot.
     */
    public List<RecurringCommitment> commitments() {
        Map<String, Classification> byId = new HashMap<>();
        for (LedgerEntry entry : entries) {
            byId.put(entry.transaction().externalId(), entry.classification());
        }
        List<RecurringCommitment> commitments = new ArrayList<>();
        for (RecurringStream stream : streams) {
            if (!stream.active() || stream.direction() != RecurringStream.Direction.MONEY_OUT) {
                continue;
            }
            SpendCategory category = categoryOf(stream, byId);
            if (category == null || category.commitment() != Commitment.FIXED) {
                continue;
            }
            commitments.add(new RecurringCommitment(
                    stream.streamId(),
                    stream.label(),
                    category,
                    stream.monthlyAmount(),
                    category.defaultRigidity(),
                    stream.nextExpected()));
        }
        commitments.sort(Comparator.comparing(RecurringCommitment::label));
        return List.copyOf(commitments);
    }

    /**
     * What each merchant in one category costs this user on an average visit, that month.
     *
     * <p>Purchases only. A refund is not a cheap visit, and a pending restaurant charge is the bill
     * before the tip, so counting either would quote the user a price they will not be charged.
     */
    public List<MerchantAverage> merchantAverages(SpendCategory category, YearMonth month) {
        Map<String, List<LedgerEntry>> byMerchant = new HashMap<>();
        for (LedgerEntry entry : entriesIn(month)) {
            NormalisedTransaction transaction = entry.transaction();
            boolean countable = transaction.classification().kind() == TransactionKind.SPEND
                    && transaction.classification().category() == category
                    && SignConvention.isMoneyOut(transaction.amount())
                    && !transaction.pending()
                    && transaction.merchantEntityId() != null
                    && transaction.merchantName() != null;
            if (countable) {
                byMerchant
                        .computeIfAbsent(transaction.merchantEntityId(), id -> new ArrayList<>())
                        .add(entry);
            }
        }
        List<MerchantAverage> averages = new ArrayList<>();
        byMerchant.forEach((merchantId, visits) -> {
            BigDecimal total = visits.stream()
                    .map(visit -> visit.transaction().amount().amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // One division, rounded once, to the nearest cent: an average has no safe direction.
            Money average = new Money(total.divide(BigDecimal.valueOf(visits.size()), 2, RoundingMode.HALF_UP));
            averages.add(new MerchantAverage(merchantId, mostRecentName(visits), average, visits.size()));
        });
        averages.sort(Comparator.comparingInt(MerchantAverage::sampleSize)
                .reversed()
                .thenComparing(MerchantAverage::merchantName));
        return List.copyOf(averages);
    }

    /** The latest classification among the stream's own transactions, which beats the stream's own label. */
    private static SpendCategory categoryOf(RecurringStream stream, Map<String, Classification> byId) {
        return stream.memberIds().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(classification -> classification.kind() == TransactionKind.SPEND)
                .map(Classification::category)
                .findFirst()
                .orElse(null);
    }

    private static String mostRecentName(List<LedgerEntry> visits) {
        return visits.stream()
                .max(Comparator.comparing(visit -> visit.transaction().date()))
                .map(visit -> visit.transaction().merchantName())
                .orElseThrow();
    }

    private static Money atLeastNothing(Money amount) {
        return amount.isNegative() ? Money.ZERO : amount;
    }
}
