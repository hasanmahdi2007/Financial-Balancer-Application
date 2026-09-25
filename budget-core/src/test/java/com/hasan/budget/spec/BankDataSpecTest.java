package com.hasan.budget.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.ingestion.application.AccessTokenCipher;
import com.hasan.budget.ingestion.application.IngestionService;
import com.hasan.budget.ingestion.domain.BankConnection;
import com.hasan.budget.ingestion.domain.BankWebhook;
import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.ingestion.domain.Ledger;
import com.hasan.budget.ingestion.domain.LedgerEntry;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.ingestion.domain.RecurringCommitment;
import com.hasan.budget.ingestion.domain.SignConvention;
import com.hasan.budget.ingestion.domain.SpendingSummary;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.plaid.PfcMapping;
import com.hasan.budget.ingestion.plaid.PlaidBankDataProvider;
import com.hasan.budget.ingestion.plaid.PlaidWebhookTranslator;
import com.hasan.budget.ingestion.plaid.RecordedSandbox;
import com.hasan.budget.ingestion.support.InMemoryBankStore;
import com.hasan.budget.ingestion.support.LogCapture;
import com.hasan.budget.ingestion.support.RecordingBankDataProvider;
import com.hasan.budget.ingestion.support.TestExecutors;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Executable specification for bank ingestion (Stage 5).
 *
 * <p>Every case here runs against recorded Plaid Sandbox responses held as fixtures. No test in
 * this class may touch the network: the machine's DNS drops intermittently, and a suite that fails
 * for connectivity reasons stops being trusted within a week.
 *
 * <p>The recordings go through the real adapter - real request building, real JSON, real category
 * mapping - so these tests meet the data as it actually arrives, including the parts of it that
 * contradict Plaid's own documentation.
 */
class BankDataSpecTest {

    private static final String USER = "user-under-test";
    private static final String OTHER_USER = "someone-else";

    /** The recorder redacts the access token before writing the fixture; this is what it writes. */
    private static final String THE_ACCESS_TOKEN = "access-sandbox-REDACTED";

    private final InMemoryBankStore store = new InMemoryBankStore();
    private final AccessTokenCipher cipher = new AccessTokenCipher(aRandomKey());
    private RecordedSandbox sandbox;

    @Nested
    @DisplayName("the sign convention, which is the opposite of the intuitive one")
    class Signs {

        /**
         * Plaid: "Positive values when money moves out of the account; negative values when money
         * moves in." A $700 salary deposit therefore arrives as amount = -700 and must be shown as
         * +$700. Getting this backwards inverts every number in the product without any error.
         */
        @Test
        void anIncomingDepositArrivesNegativeAndIsDisplayedAsPositive() {
            loadEverything();

            NormalisedTransaction payday = find(entry -> "Sweetgreen".equals(entry.transaction().merchantName())
                    && entry.transaction().amount().isNegative());

            assertThat(payday.amount()).isEqualTo(Money.of("-810.00"));
            assertThat(SignConvention.shownToUser(payday.amount())).isEqualTo(Money.of("810.00"));
            assertThat(payday.isOutflow()).isFalse();
        }

        /** A debit-card purchase arrives positive and is displayed as a negative movement. */
        @Test
        void anOutgoingPurchaseArrivesPositiveAndIsDisplayedAsNegative() {
            loadEverything();

            NormalisedTransaction netflix = find(entry -> "Netflix".equals(entry.transaction().merchantName()));

            assertThat(netflix.amount()).isEqualTo(Money.of("19.57"));
            assertThat(SignConvention.shownToUser(netflix.amount())).isEqualTo(Money.of("-19.57"));
            assertThat(netflix.isOutflow()).isTrue();
        }

        /** A refund is an inflow that reduces spending in its category rather than adding income. */
        @Test
        void aRefundReducesCategorySpendingRatherThanAddingIncome() {
            loadEverything();

            // A real $34.99 Amazon refund, which arrived tagged with the shopping category it
            // reverses rather than as any kind of deposit.
            LedgerEntry refund = entry(entry -> "Amazon".equals(entry.transaction().merchantName())
                    && entry.transaction().amount().isNegative());
            SpendCategory category = refund.classification().category();
            YearMonth month = YearMonth.from(refund.transaction().date());

            assertThat(refund.classification().kind()).isEqualTo(TransactionKind.SPEND);

            // The same month, with and without it: the difference is the whole of its effect.
            SpendingSummary asItHappened = summaryFor(month);
            SpendingSummary hadItNotBeenRefunded = Ledger.of(
                            store.entriesForUser(USER).stream()
                                    .filter(entry -> !entry.equals(refund))
                                    .toList(),
                            store.streamsForUser(USER))
                    .summaryFor(month);

            assertThat(hadItNotBeenRefunded.spentOn(category).minus(asItHappened.spentOn(category)))
                    .isEqualTo(Money.of("34.99"));
            assertThat(asItHappened.income()).isEqualTo(hadItNotBeenRefunded.income());
        }
    }

    @Nested
    @DisplayName("classification into kind and category")
    class Classifying {

        /**
         * The mapping is built from what the sandbox actually returns, not from the documentation's
         * category list. Every detailed PFC value present in the fixtures must map explicitly.
         */
        @Test
        void everyCategoryPresentInTheFixturesIsExplicitlyMapped() {
            Set<String> known = PfcMapping.fromClasspath().knownCategories();

            assertThat(categoriesInTheFixtures())
                    .isNotEmpty()
                    .allSatisfy(category -> assertThat(known).contains(category));

            // And nothing in the recorded history falls through while being imported for real.
            loadEverything();
            assertThat(sandbox.unmappedCategoryCount()).isZero();
        }

        /**
         * An unrecognised value lands in OTHER, increments a counter and is logged. It is never
         * silently dropped, because silently dropping spending makes the surplus look better than
         * it is — the one direction of error the user must never be shown.
         */
        @Test
        void anUnmappedCategoryFallsBackToOtherAndIsCounted() {
            RecordedSandbox withAnUnknownCategory = RecordedSandbox.serving(aPageCategorised("NEW_THING_NOBODY_MAPPED"));

            SyncResult result;
            try (LogCapture logs = LogCapture.start()) {
                result = withAnUnknownCategory.provider().sync(THE_ACCESS_TOKEN, null);

                assertThat(logs.everything()).contains("NEW_THING_NOBODY_MAPPED");
            }

            assertThat(result.added()).singleElement().satisfies(transaction -> {
                assertThat(transaction.classification().kind()).isEqualTo(TransactionKind.SPEND);
                assertThat(transaction.classification().category()).isEqualTo(SpendCategory.OTHER);
                assertThat(transaction.amount()).isEqualTo(Money.of("12.50"));
            });
            assertThat(withAnUnknownCategory.unmappedCategoryCount()).isEqualTo(1);
        }

        /** Groceries and dining out must not collapse into one bucket: one is capped, one is cuttable. */
        @Test
        void groceriesAndDiningOutAreSeparateCategories() {
            loadEverything();
            SpendingSummary summary = summaryForTheScenarioMonth();

            assertThat(find(entry -> "Food Lion".equals(entry.transaction().merchantName())).classification())
                    .isEqualTo(Classification.spend(SpendCategory.GROCERIES));
            assertThat(find(entry -> "McDonald's".equals(entry.transaction().merchantName())).classification())
                    .isEqualTo(Classification.spend(SpendCategory.DINING_OUT));

            assertThat(summary.spentOn(SpendCategory.GROCERIES).isPositive()).isTrue();
            assertThat(summary.spentOn(SpendCategory.DINING_OUT).isPositive()).isTrue();
            assertThat(SpendCategory.GROCERIES.baselinePolicy())
                    .isNotEqualTo(SpendCategory.DINING_OUT.baselinePolicy());
        }
    }

    @Nested
    @DisplayName("transfers and the double-count bug")
    class Transfers {

        /** TRANSFER_OUT_SAVINGS and account transfers classify as TRANSFER_INTERNAL, not spending. */
        @Test
        void movingMoneyToSavingsIsNotClassifiedAsSpending() {
            loadEverything();

            LedgerEntry toSavings = entry(entry -> entry.transaction().amount().equals(Money.of("250.00")));

            assertThat(toSavings.classification().kind()).isEqualTo(TransactionKind.TRANSFER_INTERNAL);
            assertThat(toSavings.classification().category()).isNull();

            SpendingSummary summary = Ledger.of(List.of(toSavings), List.of())
                    .summaryFor(YearMonth.from(toSavings.transaction().date()));
            assertThat(summary.totalSpending()).isEqualTo(Money.ZERO);
            // Not spending, and not nothing either: it is money the user put by, and shown as such.
            assertThat(summary.alreadySaving()).isEqualTo(Money.of("250.00"));
        }

        /**
         * LOAN_PAYMENTS_CREDIT_CARD_PAYMENT is an internal transfer. The sandbox user
         * {@code user_transactions_dynamic} has both a checking and a credit account, so this is
         * directly exercisable rather than hypothetical.
         */
        @Test
        void aCreditCardPaymentIsNotCountedAsSpend() {
            loadEverything();

            LedgerEntry paidFromChecking = entry(entry -> entry.transaction().amount().equals(Money.of("2835.80")));
            LedgerEntry seenOnTheCard = entry(entry -> entry.transaction().amount().equals(Money.of("-2835.80")));

            assertThat(paidFromChecking.classification().kind()).isEqualTo(TransactionKind.TRANSFER_INTERNAL);
            // The card side arrives tagged INCOME_SALARY. Believing it would invent $2,835.80 of pay.
            assertThat(seenOnTheCard.classification().kind()).isEqualTo(TransactionKind.TRANSFER_INTERNAL);

            SpendingSummary summary = Ledger.of(List.of(paidFromChecking, seenOnTheCard), List.of())
                    .summaryFor(YearMonth.from(paidFromChecking.transaction().date()));
            assertThat(summary.totalSpending()).isEqualTo(Money.ZERO);
            assertThat(summary.income()).isEqualTo(Money.ZERO);
            // Nor is clearing a card balance money put by, though it is equally "not spending".
            assertThat(summary.alreadySaving()).isEqualTo(Money.ZERO);
        }

        /**
         * With both accounts linked, one internal transfer appears twice — once as a debit, once as
         * a credit. Opposite signs, equal magnitude, within three days, same Item ⇒ collapse to one.
         */
        @Test
        void aTransferVisibleFromBothSidesIsCollapsedToOneRecord() {
            loadEverything();

            LedgerEntry paidFromChecking = entry(entry -> entry.transaction().amount().equals(Money.of("2835.80")));
            LedgerEntry seenOnTheCard = entry(entry -> entry.transaction().amount().equals(Money.of("-2835.80")));

            List<LedgerEntry> collapsed =
                    Ledger.of(List.of(paidFromChecking, seenOnTheCard), List.of()).entries();

            assertThat(collapsed).singleElement().satisfies(kept -> {
                // The outflow survives: it is the half where the user did something.
                assertThat(kept.transaction().externalId())
                        .isEqualTo(paidFromChecking.transaction().externalId());
                assertThat(kept.transaction().amount()).isEqualTo(Money.of("2835.80"));
            });
        }

        /** An unmatched half of a transfer still classifies correctly from its PFC value alone. */
        @Test
        void anUnmatchedTransferStillClassifiesFromItsCategory() {
            loadEverything();

            // The savings account is not connected, so only the outgoing half was ever imported.
            LedgerEntry toSavings = entry(entry -> entry.transaction().amount().equals(Money.of("250.00")));

            List<LedgerEntry> survivors = Ledger.of(List.of(toSavings), List.of()).entries();

            // Its category alone still says everything: an internal transfer, and one that adds to
            // savings rather than settling a debt - with no second half to corroborate it.
            assertThat(survivors).singleElement().satisfies(kept -> assertThat(kept.classification())
                    .isEqualTo(Classification.savings()));
        }
    }

    @Nested
    @DisplayName("sync, cursors and idempotency")
    class Sync {

        /** The cursor is persisted, so a restart resumes rather than re-importing from the start. */
        @Test
        void theCursorSurvivesARestart() {
            sandbox = RecordedSandbox.withRecordedHistory();
            RecordingBankDataProvider watched = new RecordingBankDataProvider(sandbox.provider());
            IngestionService before = serviceUsing(watched, TestExecutors.immediate());
            long connectionId = before.connect(USER, "public-token", Optional.of(CountryCode.US)).id();
            String cursorAfterTheFirstImport =
                    store.cursor(connectionId).orElseThrow();
            int importedSoFar = store.entriesForUser(USER).size();

            // The restart: a new service over the same stored data, as a redeployment would be.
            watched.forget();
            IngestionService after = serviceUsing(watched, TestExecutors.immediate());
            after.syncNow(connectionId);

            // It asked to carry on from where it stopped, rather than for the whole history again.
            assertThat(watched.cursorsAskedFor()).containsExactly(cursorAfterTheFirstImport);
            assertThat(watched.cursorsAskedFor()).doesNotContainNull();
            // The resumed page brought more, and nothing arrived twice - which is the property that
            // counting rows alone could never show, since re-importing is idempotent.
            assertThat(store.entriesForUser(USER)).hasSizeGreaterThan(importedSoFar);
            assertThat(store.entriesForUser(USER))
                    .extracting(entry -> entry.transaction().externalId())
                    .doesNotHaveDuplicates();
        }

        /** Replaying the same webhook must not duplicate a single transaction. */
        @Test
        void deliveringTheSameWebhookTwiceChangesNothing() {
            IngestionService ingestion = loadEverything();
            BankWebhook notification = new PlaidWebhookTranslator()
                    .translate(RecordedSandbox.fixture("constructed/webhook-sync-updates-available.json"));
            long connectionId = theConnection().id();
            List<LedgerEntry> before = store.entriesForUser(USER);
            String cursorBefore = store.cursor(connectionId).orElseThrow();

            ingestion.onWebhook(notification);
            ingestion.onWebhook(notification);

            assertThat(store.entriesForUser(USER)).isEqualTo(before);
            assertThat(store.cursor(connectionId)).contains(cursorBefore);
        }

        /** A removed transaction disappears from the plan; Plaid does report these. */
        @Test
        void aRemovedTransactionIsRemovedFromTheBreakdown() {
            sandbox = RecordedSandbox.serving(RecordedSandbox.fixture("sync-initial-page-1.json"));
            IngestionService ingestion = serviceUsing(sandbox.provider(), TestExecutors.immediate());
            long connectionId = ingestion.connect(USER, "public-token", Optional.of(CountryCode.US)).id();

            // A real meal out of this user's real history, retracted the way a bank retracts one.
            NormalisedTransaction doomed = find(entry -> entry.classification()
                            .equals(Classification.spend(SpendCategory.DINING_OUT))
                    && entry.transaction().isOutflow()
                    && !entry.transaction().pending());
            YearMonth month = YearMonth.from(doomed.date());
            Money diningBefore = summaryFor(month).spentOn(SpendCategory.DINING_OUT);

            sandbox = RecordedSandbox.serving(
                    RecordedSandbox.fixture("sync-initial-page-1.json"), retractionOf(doomed));
            serviceUsing(sandbox.provider(), TestExecutors.immediate()).syncNow(connectionId);

            assertThat(store.entriesForUser(USER))
                    .noneSatisfy(entry ->
                            assertThat(entry.transaction().externalId()).isEqualTo(doomed.externalId()));
            assertThat(summaryFor(month).spentOn(SpendCategory.DINING_OUT))
                    .isEqualTo(diningBefore.minus(doomed.amount()));
        }

        /** Pending transactions may change amount before settling and must not be treated as final. */
        @Test
        void pendingTransactionsAreNotTreatedAsSettled() {
            IngestionService ingestion = loadEverything();
            LedgerEntry stillPending = entry(entry -> entry.transaction().pending()
                    && entry.transaction().classification().kind() == TransactionKind.SPEND);
            YearMonth month = YearMonth.from(stillPending.transaction().date());
            assertThat(summaryFor(month).pendingSpending().isPositive()).isTrue();

            // The restaurant settles the bill with the tip added: same transaction, larger amount.
            NormalisedTransaction settled = withAmount(
                    stillPending.transaction(),
                    stillPending.transaction().amount().plus(Money.of("5.00")),
                    false);
            int countBefore = store.entriesForUser(USER).size();
            store.apply(
                    theConnection().id(),
                    store.cursor(theConnection().id()).orElseThrow(),
                    List.of(SyncResult.of(List.of(), List.of(settled), List.of(), "cursor-after-settling", false)));

            assertThat(store.entriesForUser(USER)).hasSize(countBefore);
            NormalisedTransaction now =
                    find(entry -> entry.transaction().externalId().equals(settled.externalId()));
            assertThat(now.amount()).isEqualTo(settled.amount());
            assertThat(now.pending()).isFalse();
            assertThat(summaryFor(month).pendingSpending())
                    .isEqualTo(pendingTotalOf(month));
        }

        /** A sync must never run on the request path; it is async and the API returns immediately. */
        @Test
        void aSyncNeverBlocksAUserRequest() {
            sandbox = RecordedSandbox.withRecordedHistory();
            RecordingBankDataProvider watched = new RecordingBankDataProvider(sandbox.provider());
            TestExecutors.Queueing later = TestExecutors.queueing();
            IngestionService ingestion = serviceUsing(watched, later);

            BankConnection connection = ingestion.connect(USER, "public-token", Optional.of(CountryCode.US));

            // The caller already has an answer, and the bank has not been asked for anything yet.
            assertThat(connection.id()).isPositive();
            assertThat(watched.cursorsAskedFor()).isEmpty();
            assertThat(store.entriesForUser(USER)).isEmpty();
            assertThat(later.pending()).isEqualTo(1);

            later.runQueuedWork();

            assertThat(store.entriesForUser(USER)).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("recurring commitments")
    class Recurring {

        /** A detected rent stream lands in fixed commitments, where it is never capped. */
        @Test
        void aDetectedRentStreamBecomesAFixedCommitment() {
            loadEverythingWithRent();

            RecurringCommitment rent = commitment("Oakwood Apartments");

            assertThat(rent.category()).isEqualTo(SpendCategory.RENT);
            assertThat(rent.monthlyAmount()).isEqualTo(Money.of("1450.00"));
            assertThat(rent.category().baselinePolicy()).isEqualTo(com.hasan.budget.shared.BaselinePolicy.TAKE_AS_IS);
            assertThat(rent.rigidity()).isEqualTo(Rigidity.LOCKED);
            assertThat(rent.isCuttable()).isFalse();
        }

        /** Subscriptions are detected as recurring yet remain cuttable — the two-axis case. */
        @Test
        void subscriptionsAreRecurringButStillCuttable() {
            loadEverythingWithRent();

            RecurringCommitment netflix = commitment("Netflix");

            // Arrives as entertainment; repeating every month is what makes it a subscription.
            assertThat(netflix.category()).isEqualTo(SpendCategory.SUBSCRIPTIONS);
            assertThat(netflix.category().commitment()).isEqualTo(com.hasan.budget.shared.Commitment.FIXED);
            assertThat(netflix.monthlyAmount()).isEqualTo(Money.of("19.57"));
            assertThat(netflix.isCuttable()).isTrue();
            assertThat(netflix.rigidity()).isNotEqualTo(Rigidity.LOCKED);
        }

        /** Merchant identity, name and location are persisted from the first import, for later use. */
        @Test
        void merchantIdentityAndLocationArePersistedFromTheStart() {
            loadEverything();

            NormalisedTransaction located = find(entry -> entry.transaction().latitude() != null);
            assertThat(located.merchantEntityId()).isNotBlank();
            assertThat(located.merchantName()).isNotBlank();
            assertThat(located.longitude()).isNotNull();

            // And the identifier is already enough to price a merchant, with no backfill.
            // A purchase, not the payroll deposit that also arrives under a restaurant category.
            NormalisedTransaction aMeal = find(entry ->
                    entry.classification().equals(Classification.spend(SpendCategory.DINING_OUT))
                            && entry.transaction().merchantEntityId() != null
                            && entry.transaction().isOutflow()
                            && !entry.transaction().pending());
            assertThat(ledger().merchantAverages(SpendCategory.DINING_OUT, YearMonth.from(aMeal.date())))
                    .anySatisfy(average -> {
                        assertThat(average.merchantEntityId()).isEqualTo(aMeal.merchantEntityId());
                        assertThat(average.averageTicket().isPositive()).isTrue();
                        assertThat(average.sampleSize()).isPositive();
                    });
        }
    }

    /**
     * The read-only guarantee, asserted structurally rather than trusted. The link token requests
     * only the transactions product, so money movement is not declined — it is ungranted, because
     * that needs auth or transfer and neither is ever requested.
     */
    @Test
    void theLinkTokenRequestsOnlyTheTransactionsProduct() {
        String request = RecordedSandbox.linkTokenRequestBody();

        assertThat(request).contains("\"products\":[\"transactions\"]");
        assertThat(request)
                .doesNotContain("auth")
                .doesNotContain("transfer")
                .doesNotContain("additional_consented_products")
                .doesNotContain("optional_products")
                .doesNotContain("required_if_supported_products");
    }

    /** Access tokens are encrypted at rest and never appear in logs or API responses. */
    @Test
    void accessTokensAreEncryptedAtRestAndNeverLogged() {
        sandbox = RecordedSandbox.withRecordedHistory();
        IngestionService ingestion = serviceUsing(sandbox.provider(), TestExecutors.immediate());

        BankConnection connection;
        String logged;
        try (LogCapture logs = LogCapture.start()) {
            connection = ingestion.connect(USER, "public-token", Optional.of(CountryCode.US));
            ingestion.syncNow(connection.id());
            logged = logs.everything();
        }

        String stored = connection.accessToken().value();
        assertThat(stored).doesNotContain(THE_ACCESS_TOKEN).startsWith("v1:");
        assertThat(cipher.decrypt(connection.accessToken(), USER)).isEqualTo(THE_ACCESS_TOKEN);
        assertThat(logged).doesNotContain(THE_ACCESS_TOKEN);
        // Nothing that carries a connection around can print the credential by accident either.
        assertThat(connection.toString()).doesNotContain(THE_ACCESS_TOKEN).doesNotContain(stored);
        // And the ciphertext is bound to its owner, so a row copied elsewhere is inert.
        assertThat(cipherFailsFor(connection, OTHER_USER)).isTrue();
    }

    // --- the world these cases run in -----------------------------------------------------------

    private IngestionService serviceUsing(
            com.hasan.budget.ingestion.port.BankDataProvider banks, Executor executor) {
        PlaidBankDataProvider plaid = sandbox.provider();
        return new IngestionService(plaid, banks, plaid, store, store, cipher, executor);
    }

    /** Connects the bank and imports every recorded page, as a user who has been here a while. */
    private IngestionService loadEverything() {
        if (sandbox == null) {
            sandbox = RecordedSandbox.withRecordedHistory();
        }
        IngestionService ingestion = serviceUsing(sandbox.provider(), TestExecutors.immediate());
        long connectionId = ingestion.connect(USER, "public-token", Optional.of(CountryCode.US)).id();
        while (ingestion.syncNow(connectionId).changedAnything()) {
            // Each recorded page was captured after the one before it; keep going until it is quiet.
        }
        return ingestion;
    }

    /**
     * As above, then a rent stream over the rent payment that was actually imported.
     *
     * <p>The stream fixture names its member by placeholder rather than by a recorded identifier,
     * and it is filled in here from the transaction this run really loaded. Pinning it to an
     * identifier from one particular recording would mean re-recording the fixtures broke this test
     * with a message about a missing transaction rather than about rent.
     */
    private IngestionService loadEverythingWithRent() {
        IngestionService ingestion = loadEverything();
        NormalisedTransaction rent = find(entry -> entry.classification().category() == SpendCategory.RENT);

        sandbox = RecordedSandbox.servingRecurring(
                RecordedSandbox.fixture("constructed/transactions-recurring-get-with-rent.json")
                        .replace("__RENT_TRANSACTION_ID__", rent.externalId())
                        .replace("__RENT_ACCOUNT_ID__", rent.accountId()),
                RecordedSandbox.fixture("sync-initial-page-1.json"));
        IngestionService withStreams = serviceUsing(sandbox.provider(), TestExecutors.immediate());
        withStreams.refreshRecurring(theConnection().id());
        return ingestion;
    }

    private Ledger ledger() {
        return Ledger.of(store.entriesForUser(USER), store.streamsForUser(USER));
    }

    private SpendingSummary summaryFor(YearMonth month) {
        return ledger().summaryFor(month);
    }

    /** The month carrying the prompted rows, found from the data rather than from a clock. */
    private SpendingSummary summaryForTheScenarioMonth() {
        return summaryFor(YearMonth.from(
                find(entry -> entry.transaction().amount().equals(Money.of("1450.00"))).date()));
    }

    private BankConnection theConnection() {
        return store.forUser(USER).get(0);
    }

    private RecurringCommitment commitment(String label) {
        return ledger().commitments().stream()
                .filter(commitment -> commitment.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no commitment called " + label + " in "
                        + ledger().commitments().stream()
                                .map(RecurringCommitment::label)
                                .collect(Collectors.joining(", "))));
    }

    private LedgerEntry entry(Predicate<LedgerEntry> wanted) {
        return store.entriesForUser(USER).stream()
                .filter(wanted)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such transaction in the recorded fixtures"));
    }

    private NormalisedTransaction find(Predicate<LedgerEntry> wanted) {
        return entry(wanted).transaction();
    }

    private Money pendingTotalOf(YearMonth month) {
        return ledger().entriesIn(month).stream()
                .filter(entry -> entry.transaction().pending())
                .filter(entry -> entry.classification().kind() == TransactionKind.SPEND)
                .map(entry -> entry.transaction().amount())
                .reduce(Money.ZERO, Money::plus);
    }

    private boolean cipherFailsFor(BankConnection connection, String pretendingToBe) {
        try {
            cipher.decrypt(connection.accessToken(), pretendingToBe);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static NormalisedTransaction withAmount(
            NormalisedTransaction original, Money amount, boolean pending) {
        return new NormalisedTransaction(
                original.externalId(),
                original.accountId(),
                original.date(),
                amount,
                original.merchantName(),
                original.merchantEntityId(),
                original.latitude(),
                original.longitude(),
                original.classification(),
                pending);
    }

    /** Every category value in every committed fixture, recorded and constructed alike. */
    private static Set<String> categoriesInTheFixtures() {
        Pattern detailed = Pattern.compile("\"detailed\"\\s*:\\s*\"([A-Z_]+)\"");
        List<String> files = List.of(
                "sync-initial-page-1.json",
                "sync-after-refresh-page-1.json",
                "sync-scenarios-page-1.json",
                "transactions-recurring-get.json",
                "transactions-recurring-get-scenarios.json",
                "constructed/transactions-recurring-get-with-rent.json");
        return files.stream()
                .map(RecordedSandbox::fixture)
                .flatMap(body -> {
                    Matcher matcher = detailed.matcher(body);
                    return matcher.results().map(result -> result.group(1));
                })
                .collect(Collectors.toSet());
    }

    /** A page in which the bank says one transaction it sent before is gone, in its own format. */
    private static String retractionOf(NormalisedTransaction doomed) {
        return """
                {"accounts":[],"added":[],"modified":[],
                 "removed":[{"account_id":"%s","transaction_id":"%s"}],
                 "next_cursor":"cursor-after-the-retraction","has_more":false}"""
                .formatted(doomed.accountId(), doomed.externalId());
    }

    private static String aPageCategorised(String detailedCategory) {
        return """
                {"accounts":[{"account_id":"acc-1","name":"Checking","type":"depository","subtype":"checking"}],
                 "added":[{"transaction_id":"t-1","account_id":"acc-1","amount":12.50,"date":"2026-09-15",
                           "name":"Something we have not seen","pending":false,
                           "personal_finance_category":{"primary":"NEW","detailed":"%s","confidence_level":"LOW"}}],
                 "modified":[],"removed":[],"next_cursor":"cursor-1","has_more":false}"""
                .formatted(detailedCategory);
    }

    private static String aRandomKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
