package com.hasan.budget.ingestion.plaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.hasan.budget.ingestion.domain.SyncInterrupted;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.support.LogCapture;
import com.hasan.budget.shared.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The adapter, against the bytes Plaid really sent and the errors it really returns.
 *
 * <p>Nothing here reaches the network: the recorded responses are replayed through the real client,
 * so the request building, the JSON reading and the category mapping are all the production ones.
 */
class PlaidBankDataProviderTest {

    private static final String TOKEN = "access-sandbox-whatever";

    @Test
    @DisplayName("credentials travel as headers, never in the body that gets logged")
    void credentialsAreSentAsHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RecordedSandbox.BASE_URL + "/item/get"))
                .andExpect(header("PLAID-CLIENT-ID", "test-client-id"))
                .andExpect(header("PLAID-SECRET", "test-secret"))
                .andRespond(withSuccess(RecordedSandbox.fixture("item-get.json"), MediaType.APPLICATION_JSON));

        String itemId = providerFor(builder, configured()).itemIdFor(TOKEN);

        assertThat(itemId).isNotBlank();
        server.verify();
    }

    @Test
    @DisplayName("a recorded page becomes transactions with their merchant, location and sign intact")
    void aRecordedSyncIsParsedFaithfully() {
        SyncResult result = RecordedSandbox.withRecordedHistory().provider().sync(TOKEN, null);

        assertThat(result.added()).hasSizeGreaterThan(100);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNotBlank();
        assertThat(result.added())
                .anySatisfy(transaction -> {
                    assertThat(transaction.merchantEntityId()).isNotBlank();
                    assertThat(transaction.latitude()).isNotNull();
                    assertThat(transaction.longitude()).isNotNull();
                })
                // Money out is positive, money in is negative, exactly as Plaid sends it.
                .anySatisfy(transaction -> assertThat(transaction.amount().isNegative()).isTrue())
                .allSatisfy(transaction -> {
                    assertThat(transaction.externalId()).isNotBlank();
                    assertThat(transaction.accountId()).isNotBlank();
                    assertThat(transaction.date()).isNotNull();
                });
    }

    @Test
    @DisplayName("an amount with sub-cent precision becomes cents, rounded to the nearest")
    void subCentAmountsAreBroughtToMoney() {
        SyncResult result = RecordedSandbox.serving(aPageWithAmount("48.2542")).provider().sync(TOKEN, null);

        assertThat(result.added()).singleElement().satisfies(transaction -> assertThat(transaction.amount())
                .isEqualTo(Money.of("48.25")));
    }

    @Test
    @DisplayName("the date used is when the user spent it, not when the bank got round to posting")
    void theAuthorisedDateWinsWhenThereIsOne() {
        SyncResult result = RecordedSandbox.serving(aPageDated("2026-10-02", "2026-09-30"))
                .provider()
                .sync(TOKEN, null);

        assertThat(result.added())
                .singleElement()
                .satisfies(transaction ->
                        assertThat(transaction.date()).isEqualTo(LocalDate.of(2026, 9, 30)));
    }

    @Test
    @DisplayName("a transaction with no category at all is still spending rather than nothing")
    void aMissingCategoryIsNotAMissingTransaction() {
        RecordedSandbox sandbox = RecordedSandbox.serving(aPageWithNoCategory());

        SyncResult result = sandbox.provider().sync(TOKEN, null);

        assertThat(result.added()).singleElement().satisfies(transaction -> assertThat(
                        transaction.classification().category())
                .isEqualTo(com.hasan.budget.shared.SpendCategory.OTHER));
        assertThat(sandbox.unmappedCategoryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown credit does not quietly reduce somebody's spending")
    void anUnmappedInflowIsNotBookedAsNegativeSpending() {
        // The fallback for an unknown payment is spending in "Everything else". Sending a credit the
        // same way stores a negative amount in a category, which subtracts from that category's
        // total - so an unrecognised deposit would shrink apparent spending and inflate the surplus,
        // the one direction of error this module exists to prevent. It is not income either: money
        // arriving unidentified is as likely to be borrowed as earned.
        RecordedSandbox sandbox = RecordedSandbox.serving(aCreditCategorised("SOMETHING_NEW_AND_INCOMING"));

        SyncResult result = sandbox.provider().sync(TOKEN, null);

        assertThat(result.added()).singleElement().satisfies(transaction -> {
            assertThat(transaction.classification().kind())
                    .isEqualTo(com.hasan.budget.shared.TransactionKind.TRANSFER_EXTERNAL);
            assertThat(transaction.classification().category()).isNull();
            assertThat(transaction.amount()).isEqualTo(Money.of("-250.00"));
        });
        assertThat(sandbox.unmappedCategoryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("retractions are read from the page and reported as identifiers")
    void removalsArriveAsIds() {
        SyncResult result = RecordedSandbox.serving(
                        RecordedSandbox.fixture("constructed/sync-removes-one-purchase.json"))
                .provider()
                .sync(TOKEN, null);

        assertThat(result.removedExternalIds()).hasSize(1);
        assertThat(result.added()).isEmpty();
    }

    @Test
    @DisplayName("the balances that come with the transactions are kept, and labelled by kind")
    void accountBalancesArriveWithTheSync() {
        SyncResult result = RecordedSandbox.withRecordedHistory().provider().sync(TOKEN, null);

        assertThat(result.accounts()).isNotEmpty();
        assertThat(result.accounts())
                .filteredOn(account -> account.role() == com.hasan.budget.ingestion.domain.AccountRole.CASH)
                .isNotEmpty()
                .allSatisfy(account -> {
                    assertThat(account.holdsMoney()).isTrue();
                    assertThat(account.label()).isNotBlank();
                    assertThat(account.current()).isNotNull();
                });
        // The card and the loans are in the list too, and none of them holds spendable money:
        // adding what is owed on them to somebody's funds would invent money that is not there.
        assertThat(result.accounts())
                .filteredOn(account -> !account.holdsMoney())
                .isNotEmpty()
                .allSatisfy(account -> assertThat(account.role())
                        .isIn(
                                com.hasan.budget.ingestion.domain.AccountRole.CARD,
                                com.hasan.budget.ingestion.domain.AccountRole.LOAN));
    }

    @Test
    @DisplayName("a stream with no amount is skipped rather than taking the sync down")
    void aStreamWithoutAnAmountCannotBreakAnImport() {
        RecordedSandbox sandbox = RecordedSandbox.servingRecurring(
                """
                        {"inflow_streams":[],"outflow_streams":[
                          {"stream_id":"s-average-only","account_id":"acc-1","description":"Gym",
                           "merchant_name":"Planet Fitness","frequency":"MONTHLY","status":"MATURE",
                           "is_active":true,"last_date":"2026-09-01","transaction_ids":["t-1"],
                           "average_amount":{"amount":29.00,"iso_currency_code":"USD"}},
                          {"stream_id":"s-no-amount-at-all","account_id":"acc-1","description":"Mystery",
                           "frequency":"MONTHLY","status":"MATURE","is_active":true,
                           "transaction_ids":["t-2"]}]}""",
                RecordedSandbox.fixture("sync-initial-page-1.json"));

        var streams = sandbox.provider().recurringStreams(TOKEN);

        // The one that can be valued survives, at its average; the one that cannot is dropped, and
        // its transactions are stored and counted regardless, so only a label is lost.
        assertThat(streams).singleElement().satisfies(stream -> {
            assertThat(stream.streamId()).isEqualTo("s-average-only");
            assertThat(stream.monthlyAmount()).isEqualTo(Money.of("29.00"));
        });
    }

    @Test
    @DisplayName("detected streams keep the merchant's name and the latest amount")
    void recurringStreamsAreParsed() {
        var streams = RecordedSandbox.withRecordedHistory().provider().recurringStreams(TOKEN);

        assertThat(streams).isNotEmpty().anySatisfy(stream -> {
            assertThat(stream.label()).isNotBlank();
            assertThat(stream.memberIds()).isNotEmpty();
            assertThat(stream.monthlyAmount()).isNotNull();
        });
    }

    @Test
    @DisplayName("a Plaid error becomes a typed failure that cannot carry the token that caused it")
    void errorsNeverCarryTheRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RecordedSandbox.BASE_URL + "/transactions/sync"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                """
                                {"error_type":"ITEM_ERROR","error_code":"ITEM_LOGIN_REQUIRED",
                                 "error_message":"the login details have changed","request_id":"abc123"}"""));

        PlaidBankDataProvider provider = providerFor(builder, configured());

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> provider.sync(TOKEN, null))
                    .isInstanceOf(PlaidApiException.class)
                    .hasMessageContaining("ITEM_LOGIN_REQUIRED")
                    .hasMessageContaining("abc123")
                    .hasMessageNotContaining(TOKEN);
            assertThat(logs.everything()).doesNotContain(TOKEN);
        }
    }

    @Test
    @DisplayName("data changing mid-pagination is its own failure, because it must restart")
    void aMutationDuringPaginationIsDistinct() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RecordedSandbox.BASE_URL + "/transactions/sync"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                """
                                {"error_type":"TRANSACTIONS_ERROR",
                                 "error_code":"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION",
                                 "error_message":"underlying data changed","request_id":"abc124"}"""));

        assertThatThrownBy(() -> providerFor(builder, configured()).sync(TOKEN, "cursor-1"))
                .isInstanceOf(SyncInterrupted.class);
    }

    @Test
    @DisplayName("without credentials it says which ones are missing, and asks nobody")
    void missingCredentialsFailBeforeAnyRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> providerFor(builder, unconfigured()).sync(TOKEN, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLAID_CLIENT_ID");

        // Nothing was expected, and nothing was sent.
        server.verify();
    }

    private static PlaidBankDataProvider providerFor(RestClient.Builder builder, PlaidProperties properties) {
        return new PlaidBankDataProvider(
                PlaidClient.create(builder, properties),
                new PlaidTransactionMapper(PfcMapping.fromClasspath(), new SimpleMeterRegistry()),
                properties);
    }

    private static PlaidProperties configured() {
        return new PlaidProperties(
                PlaidProperties.PlaidEnvironment.SANDBOX, "test-client-id", "test-secret", null, null, null);
    }

    private static PlaidProperties unconfigured() {
        return new PlaidProperties(PlaidProperties.PlaidEnvironment.SANDBOX, "", "", null, null, null);
    }

    private static String aPageWithAmount(String amount) {
        return onePage(
                """
                {"transaction_id":"t-1","account_id":"acc-1","amount":%s,"date":"2026-09-15",
                 "name":"Groceries","pending":false,
                 "personal_finance_category":{"primary":"FOOD_AND_DRINK","detailed":"FOOD_AND_DRINK_GROCERIES"}}"""
                        .formatted(amount));
    }

    private static String aPageDated(String posted, String authorised) {
        return onePage(
                """
                {"transaction_id":"t-1","account_id":"acc-1","amount":10.00,"date":"%s","authorized_date":"%s",
                 "name":"Groceries","pending":false,
                 "personal_finance_category":{"primary":"FOOD_AND_DRINK","detailed":"FOOD_AND_DRINK_GROCERIES"}}"""
                        .formatted(posted, authorised));
    }

    private static String aCreditCategorised(String detailedCategory) {
        return onePage(
                """
                {"transaction_id":"t-1","account_id":"acc-1","amount":-250.00,"date":"2026-09-15",
                 "name":"Money from somewhere","pending":false,
                 "personal_finance_category":{"primary":"NEW","detailed":"%s"}}"""
                        .formatted(detailedCategory));
    }

    private static String aPageWithNoCategory() {
        return onePage(
                """
                {"transaction_id":"t-1","account_id":"acc-1","amount":10.00,"date":"2026-09-15",
                 "name":"Something","pending":false}""");
    }

    private static String onePage(String transaction) {
        return """
                {"accounts":[{"account_id":"acc-1","name":"Checking","type":"depository","subtype":"checking"}],
                 "added":[%s],"modified":[],"removed":[],"next_cursor":"cursor-1","has_more":false}"""
                .formatted(transaction);
    }

}
