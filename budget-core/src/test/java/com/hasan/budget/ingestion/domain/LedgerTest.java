package com.hasan.budget.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic between stored rows and the few numbers a plan is built from.
 *
 * <p>Pure enough to state every case exactly. The specification exercises these rules against the
 * recorded sandbox, where the amounts are whatever a synthetic bank invented; here they are chosen,
 * so a wrong total is a wrong number rather than an unfamiliar one.
 */
class LedgerTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final LocalDate MID_SEPTEMBER = LocalDate.of(2026, 9, 15);

    @Nested
    @DisplayName("what a month adds up to")
    class Summary {

        @Test
        @DisplayName("spending is grouped by category and income is shown the way a person reads it")
        void theBasicTotals() {
            SpendingSummary summary = ledger(
                            spend("a", SpendCategory.GROCERIES, "80.00"),
                            spend("b", SpendCategory.GROCERIES, "45.50"),
                            spend("c", SpendCategory.DINING_OUT, "22.00"),
                            income("d", "-2400.00"))
                    .summaryFor(SEPTEMBER);

            assertThat(summary.spentOn(SpendCategory.GROCERIES)).isEqualTo(Money.of("125.50"));
            assertThat(summary.spentOn(SpendCategory.DINING_OUT)).isEqualTo(Money.of("22.00"));
            assertThat(summary.income()).isEqualTo(Money.of("2400.00"));
            assertThat(summary.transactionCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("a category nobody spent in reads as zero rather than as missing")
        void anUntouchedCategoryIsZero() {
            SpendingSummary summary =
                    ledger(spend("a", SpendCategory.GROCERIES, "80.00")).summaryFor(SEPTEMBER);

            assertThat(summary.spentOn(SpendCategory.RENT)).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("a refund nets against its own category")
        void refundsReduceTheirCategory() {
            SpendingSummary summary = ledger(
                            spend("a", SpendCategory.CLOTHING, "120.00"),
                            spend("b", SpendCategory.CLOTHING, "-45.00"))
                    .summaryFor(SEPTEMBER);

            assertThat(summary.spentOn(SpendCategory.CLOTHING)).isEqualTo(Money.of("75.00"));
        }

        @Test
        @DisplayName("refunds beyond what was spent floor at zero rather than reading as income")
        void aCategoryNeverGoesNegative() {
            SpendingSummary summary = ledger(
                            spend("a", SpendCategory.CLOTHING, "40.00"),
                            spend("b", SpendCategory.CLOTHING, "-100.00"))
                    .summaryFor(SEPTEMBER);

            // Floored, so the error is towards showing more spending - the only safe direction.
            assertThat(summary.spentOn(SpendCategory.CLOTHING)).isEqualTo(Money.ZERO);
            assertThat(summary.income()).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("transfers and card payments are not spending and are not income")
        void internalMovementsAreInvisibleToTheTotals() {
            SpendingSummary summary = ledger(
                            spend("a", SpendCategory.GROCERIES, "80.00"),
                            internal("b", "checking", "500.00"),
                            internal("c", "card", "-1500.00"))
                    .summaryFor(SEPTEMBER);

            assertThat(summary.totalSpending()).isEqualTo(Money.of("80.00"));
            assertThat(summary.income()).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("fees are real money out, kept separate so they can be named")
        void feesAreCountedApartFromCategories() {
            SpendingSummary summary = ledger(
                            spend("a", SpendCategory.GROCERIES, "80.00"), fee("b", "35.00"))
                    .summaryFor(SEPTEMBER);

            assertThat(summary.fees()).isEqualTo(Money.of("35.00"));
            assertThat(summary.spendingByCategory()).doesNotContainKey(SpendCategory.OTHER);
            assertThat(summary.totalSpending()).isEqualTo(Money.of("115.00"));
        }

        @Test
        @DisplayName("borrowed money is not income")
        void aCashAdvanceDoesNotCountAsEarnings() {
            SpendingSummary summary = ledger(external("a", "-95.00")).summaryFor(SEPTEMBER);

            assertThat(summary.income()).isEqualTo(Money.ZERO);
            assertThat(summary.totalSpending()).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("pending spending counts, and is also reported separately")
        void pendingIsIncludedAndVisible() {
            SpendingSummary summary = ledger(
                            spend("a", SpendCategory.DINING_OUT, "22.00"),
                            pending("b", SpendCategory.DINING_OUT, "48.25"))
                    .summaryFor(SEPTEMBER);

            assertThat(summary.spentOn(SpendCategory.DINING_OUT)).isEqualTo(Money.of("70.25"));
            assertThat(summary.pendingSpending()).isEqualTo(Money.of("48.25"));
        }

        @Test
        @DisplayName("another month's spending stays in that month")
        void monthsDoNotLeakIntoEachOther() {
            LedgerEntry august = entry(new NormalisedTransaction(
                    "old",
                    "checking",
                    LocalDate.of(2026, 8, 31),
                    Money.of("500.00"),
                    null,
                    null,
                    null,
                    null,
                    Classification.spend(SpendCategory.GROCERIES),
                    false));

            Ledger ledger = Ledger.of(List.of(august, spend("new", SpendCategory.GROCERIES, "80.00")), List.of());

            assertThat(ledger.summaryFor(SEPTEMBER).spentOn(SpendCategory.GROCERIES))
                    .isEqualTo(Money.of("80.00"));
            assertThat(ledger.summaryFor(YearMonth.of(2026, 8)).spentOn(SpendCategory.GROCERIES))
                    .isEqualTo(Money.of("500.00"));
        }
    }

    @Nested
    @DisplayName("what repeating changes")
    class Repeating {

        @Test
        @DisplayName("a recurring inflow is pay, even from a merchant we would call a restaurant")
        void aRecurringInflowBecomesIncome() {
            LedgerEntry payday = entry(new NormalisedTransaction(
                    "pay-1",
                    "checking",
                    MID_SEPTEMBER,
                    Money.of("-810.00"),
                    "Sweetgreen",
                    "merchant-sweetgreen",
                    null,
                    null,
                    Classification.spend(SpendCategory.DINING_OUT),
                    false));

            SpendingSummary summary = Ledger.of(List.of(payday), List.of(stream(
                            "payroll", RecurringStream.Direction.MONEY_IN, "Sweetgreen", "-810.00",
                            Frequency.WEEKLY, List.of("pay-1"))))
                    .summaryFor(SEPTEMBER);

            assertThat(summary.income()).isEqualTo(Money.of("810.00"));
            assertThat(summary.spentOn(SpendCategory.DINING_OUT)).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("a monthly entertainment charge becomes a subscription")
        void aRecurringDiscretionaryChargeBecomesACommitment() {
            LedgerEntry netflix = entry(new NormalisedTransaction(
                    "netflix-1",
                    "card",
                    MID_SEPTEMBER,
                    Money.of("19.57"),
                    "Netflix",
                    "merchant-netflix",
                    null,
                    null,
                    Classification.spend(SpendCategory.ENTERTAINMENT),
                    false));
            Ledger ledger = Ledger.of(List.of(netflix), List.of(stream(
                    "netflix", RecurringStream.Direction.MONEY_OUT, "Netflix", "19.57",
                    Frequency.MONTHLY, List.of("netflix-1"))));

            assertThat(ledger.summaryFor(SEPTEMBER).spentOn(SpendCategory.SUBSCRIPTIONS))
                    .isEqualTo(Money.of("19.57"));
            assertThat(ledger.commitments())
                    .singleElement()
                    .satisfies(commitment -> {
                        assertThat(commitment.label()).isEqualTo("Netflix");
                        assertThat(commitment.category()).isEqualTo(SpendCategory.SUBSCRIPTIONS);
                        assertThat(commitment.isCuttable()).isTrue();
                    });
        }

        @Test
        @DisplayName("a weekly coffee is a habit rather than a subscription")
        void aRepeatingHabitKeepsItsOwnCategory() {
            LedgerEntry coffee = entry(new NormalisedTransaction(
                    "coffee-1",
                    "checking",
                    MID_SEPTEMBER,
                    Money.of("4.75"),
                    "Starbucks",
                    "merchant-starbucks",
                    null,
                    null,
                    Classification.spend(SpendCategory.DINING_OUT),
                    false));
            Ledger ledger = Ledger.of(List.of(coffee), List.of(stream(
                    "coffee", RecurringStream.Direction.MONEY_OUT, "Starbucks", "4.75",
                    Frequency.WEEKLY, List.of("coffee-1"))));

            assertThat(ledger.summaryFor(SEPTEMBER).spentOn(SpendCategory.DINING_OUT))
                    .isEqualTo(Money.of("4.75"));
            // Eating out is not a commitment: next week's lunch can simply not be bought.
            assertThat(ledger.commitments()).isEmpty();
        }

        @Test
        @DisplayName("a stream whose transactions are not spending produces no commitment")
        void aRecurringTransferIsNotACommitment() {
            LedgerEntry toSavings = internal("save-1", "checking", "250.00");
            Ledger ledger = Ledger.of(List.of(toSavings), List.of(stream(
                    "savings", RecurringStream.Direction.MONEY_OUT, "Savings", "250.00",
                    Frequency.MONTHLY, List.of("save-1"))));

            assertThat(ledger.commitments()).isEmpty();
        }

        @Test
        @DisplayName("a stream's category is what most of its transactions say, not the first one")
        void oneOddMonthDoesNotDecideAStreamsCategory() {
            // The same merchant, categorised differently once. Taking whichever identifier the
            // provider happened to list first would make the answer depend on their ordering.
            LedgerEntry odd = entry(new NormalisedTransaction(
                    "rent-odd",
                    "checking",
                    MID_SEPTEMBER.minusMonths(2),
                    Money.of("1450.00"),
                    "Oakwood Apartments",
                    "merchant-oakwood",
                    null,
                    null,
                    Classification.spend(SpendCategory.OTHER),
                    false));
            LedgerEntry first = rent("rent-1", MID_SEPTEMBER.minusMonths(1));
            LedgerEntry second = rent("rent-2", MID_SEPTEMBER);
            RecurringStream stream = new RecurringStream(
                    "rent",
                    "checking",
                    RecurringStream.Direction.MONEY_OUT,
                    "Oakwood Apartments",
                    Frequency.MONTHLY,
                    Money.of("1450.00"),
                    MID_SEPTEMBER,
                    MID_SEPTEMBER.plusMonths(1),
                    true,
                    List.of("rent-odd", "rent-1", "rent-2"));

            assertThat(Ledger.of(List.of(odd, first, second), List.of(stream)).commitments())
                    .singleElement()
                    .satisfies(commitment -> assertThat(commitment.category()).isEqualTo(SpendCategory.RENT));
        }

        @Test
        @DisplayName("a cancelled stream stops being a commitment")
        void anInactiveStreamIsIgnored() {
            LedgerEntry gym = entry(new NormalisedTransaction(
                    "gym-1",
                    "checking",
                    MID_SEPTEMBER,
                    Money.of("29.00"),
                    "Planet Fitness",
                    "merchant-gym",
                    null,
                    null,
                    Classification.spend(SpendCategory.SUBSCRIPTIONS),
                    false));
            RecurringStream cancelled = new RecurringStream(
                    "gym",
                    "checking",
                    RecurringStream.Direction.MONEY_OUT,
                    "Planet Fitness",
                    Frequency.MONTHLY,
                    Money.of("29.00"),
                    MID_SEPTEMBER,
                    null,
                    false,
                    List.of("gym-1"));

            assertThat(Ledger.of(List.of(gym), List.of(cancelled)).commitments()).isEmpty();
        }

        @Test
        @DisplayName("a weekly commitment is converted to what it costs in a month")
        void cadenceBecomesAMonthlyFigure() {
            assertThat(Frequency.WEEKLY.monthlyEquivalent(Money.of("810.00"))).isEqualTo(Money.of("3510.00"));
            assertThat(Frequency.BIWEEKLY.monthlyEquivalent(Money.of("100.00"))).isEqualTo(Money.of("216.67"));
            assertThat(Frequency.SEMI_MONTHLY.monthlyEquivalent(Money.of("100.00"))).isEqualTo(Money.of("200.00"));
            assertThat(Frequency.MONTHLY.monthlyEquivalent(Money.of("19.57"))).isEqualTo(Money.of("19.57"));
            assertThat(Frequency.ANNUALLY.monthlyEquivalent(Money.of("120.00"))).isEqualTo(Money.of("10.00"));
            // Rounded once, at the end, to the nearest cent: 19.99 x 52 / 12 is 86.6233...
            assertThat(Frequency.WEEKLY.monthlyEquivalent(Money.of("19.99"))).isEqualTo(Money.of("86.62"));
        }

        @Test
        @DisplayName("an unfamiliar cadence is treated as monthly rather than dropped")
        void anUnknownFrequencyStillCounts() {
            assertThat(Frequency.parse("FORTNIGHTLY-ISH")).isEqualTo(Frequency.UNKNOWN);
            assertThat(Frequency.parse(null)).isEqualTo(Frequency.UNKNOWN);
            assertThat(Frequency.UNKNOWN.monthlyEquivalent(Money.of("50.00"))).isEqualTo(Money.of("50.00"));
        }
    }

    @Nested
    @DisplayName("what a merchant costs")
    class MerchantPrices {

        @Test
        @DisplayName("the average is over purchases at that merchant, to the nearest cent")
        void averagingIsOneDivisionAtTheEnd() {
            Ledger ledger = ledger(
                    atMerchant("a", "merchant-1", "Bojangles", "10.00", false),
                    atMerchant("b", "merchant-1", "Bojangles", "11.00", false),
                    atMerchant("c", "merchant-1", "Bojangles", "12.01", false));

            assertThat(ledger.merchantAverages(SpendCategory.DINING_OUT, SEPTEMBER))
                    .singleElement()
                    .satisfies(average -> {
                        // 33.01 / 3 is 11.00333...; nearest, because an average has no safe direction.
                        assertThat(average.averageTicket()).isEqualTo(Money.of("11.00"));
                        assertThat(average.sampleSize()).isEqualTo(3);
                        assertThat(average.merchantName()).isEqualTo("Bojangles");
                    });
        }

        @Test
        @DisplayName("a refund is not a cheap visit and a pending bill is missing its tip")
        void refundsAndPendingChargesAreLeftOut() {
            Ledger ledger = ledger(
                    atMerchant("a", "merchant-1", "Bojangles", "20.00", false),
                    atMerchant("b", "merchant-1", "Bojangles", "-20.00", false),
                    atMerchant("c", "merchant-1", "Bojangles", "60.00", true));

            assertThat(ledger.merchantAverages(SpendCategory.DINING_OUT, SEPTEMBER))
                    .singleElement()
                    .satisfies(average -> {
                        assertThat(average.averageTicket()).isEqualTo(Money.of("20.00"));
                        assertThat(average.sampleSize()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("merchants are grouped by identifier, so two spellings are still one place")
        void groupingIsByIdentifierRatherThanName() {
            Ledger ledger = ledger(
                    atMerchant("a", "merchant-1", "BOJANGLES #123", "10.00", false),
                    atMerchant("b", "merchant-1", "Bojangles", "20.00", false));

            assertThat(ledger.merchantAverages(SpendCategory.DINING_OUT, SEPTEMBER))
                    .singleElement()
                    .satisfies(average -> assertThat(average.sampleSize()).isEqualTo(2));
        }

        @Test
        @DisplayName("only the category asked about is answered")
        void anotherCategoryIsNotIncluded() {
            Ledger ledger = ledger(
                    atMerchant("a", "merchant-1", "Bojangles", "10.00", false),
                    entry(new NormalisedTransaction(
                            "b",
                            "checking",
                            MID_SEPTEMBER,
                            Money.of("90.00"),
                            "Food Lion",
                            "merchant-2",
                            null,
                            null,
                            Classification.spend(SpendCategory.GROCERIES),
                            false)));

            assertThat(ledger.merchantAverages(SpendCategory.DINING_OUT, SEPTEMBER))
                    .extracting(MerchantAverage::merchantEntityId)
                    .containsExactly("merchant-1");
        }

        @Test
        @DisplayName("no history is an empty answer rather than a guess")
        void nothingObservedMeansNothingReturned() {
            assertThat(Ledger.of(List.of(), List.of()).merchantAverages(SpendCategory.DINING_OUT, SEPTEMBER))
                    .isEmpty();
        }
    }

    // --- building a world -----------------------------------------------------------------------

    private static Ledger ledger(LedgerEntry... entries) {
        return Ledger.of(List.of(entries), List.of());
    }

    private static LedgerEntry entry(NormalisedTransaction transaction) {
        return new LedgerEntry(1, transaction);
    }

    private static LedgerEntry rent(String id, java.time.LocalDate when) {
        return entry(new NormalisedTransaction(
                id,
                "checking",
                when,
                Money.of("1450.00"),
                "Oakwood Apartments",
                "merchant-oakwood",
                null,
                null,
                Classification.spend(SpendCategory.RENT),
                false));
    }

    private static LedgerEntry spend(String id, SpendCategory category, String amount) {
        return entry(new NormalisedTransaction(
                id,
                "checking",
                MID_SEPTEMBER,
                Money.of(amount),
                null,
                null,
                null,
                null,
                Classification.spend(category),
                false));
    }

    private static LedgerEntry pending(String id, SpendCategory category, String amount) {
        return entry(new NormalisedTransaction(
                id,
                "checking",
                MID_SEPTEMBER,
                Money.of(amount),
                null,
                null,
                null,
                null,
                Classification.spend(category),
                true));
    }

    private static LedgerEntry atMerchant(
            String id, String merchantId, String merchantName, String amount, boolean pending) {
        return entry(new NormalisedTransaction(
                id,
                "checking",
                MID_SEPTEMBER,
                Money.of(amount),
                merchantName,
                merchantId,
                null,
                null,
                Classification.spend(SpendCategory.DINING_OUT),
                pending));
    }

    private static LedgerEntry income(String id, String amount) {
        return notSpending(id, "checking", amount, TransactionKind.INCOME);
    }

    private static LedgerEntry fee(String id, String amount) {
        return notSpending(id, "checking", amount, TransactionKind.FEE);
    }

    private static LedgerEntry external(String id, String amount) {
        return notSpending(id, "checking", amount, TransactionKind.TRANSFER_EXTERNAL);
    }

    private static LedgerEntry internal(String id, String account, String amount) {
        return notSpending(id, account, amount, TransactionKind.TRANSFER_INTERNAL);
    }

    private static LedgerEntry notSpending(String id, String account, String amount, TransactionKind kind) {
        return entry(new NormalisedTransaction(
                id,
                account,
                MID_SEPTEMBER,
                Money.of(amount),
                null,
                null,
                null,
                null,
                Classification.notSpending(kind),
                false));
    }

    private static RecurringStream stream(
            String id,
            RecurringStream.Direction direction,
            String label,
            String lastAmount,
            Frequency frequency,
            List<String> members) {
        return new RecurringStream(
                id,
                "checking",
                direction,
                label,
                frequency,
                Money.of(lastAmount),
                MID_SEPTEMBER,
                MID_SEPTEMBER.plusMonths(1),
                true,
                members);
    }
}
