package com.hasan.budget.ingestion.plaid;

import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The shapes Plaid actually sends and expects, kept together and kept out of everything else.
 *
 * <p>These are wire types, not domain types. They are nested here so that the whole of Plaid's
 * vocabulary - items, cursors, personal finance categories, streams - stops at the edge of this
 * package, and so that a second provider is a new package rather than a set of edits.
 *
 * <p>Amounts are {@link BigDecimal} rather than {@code double} even in transit. The sandbox really
 * does send figures like {@code 48.2542}, and binary floating point cannot hold most decimal amounts
 * exactly; converting through a {@code double} would introduce an error before the money type that
 * exists to prevent it ever sees the number.
 *
 * <p>Requests are records rather than maps for a reason that is load-bearing rather than tidy: a
 * link-token request that has no field for extra products cannot acquire one by accident, which is
 * what makes "this application cannot move money" a structural claim instead of a promise.
 */
final class PlaidWire {

    private PlaidWire() {}

    /**
     * A credential on its way to Plaid, wrapped so that printing the request cannot print the secret.
     *
     * <p>Found by the test that asserts tokens never reach a log: Spring logs the request object at
     * debug level, and a record generates a toString containing every component, so an access token
     * was being written out in full whenever debug logging was on. Serialised as a bare string by
     * {@code @JsonValue}, so the wire format is unchanged.
     */
    record Secret(String value) {

        @JsonValue
        @Override
        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return "***";
        }
    }

    static Secret secret(String value) {
        return new Secret(value);
    }

    // --- requests -------------------------------------------------------------------------------

    /**
     * Everything the consent widget is told to ask for.
     *
     * <p>There is deliberately no field for {@code additional_consented_products},
     * {@code optional_products} or {@code required_if_supported_products}. Moving money needs the
     * auth or transfer product, granted here or nowhere, so a payment is not something the resulting
     * token is refused - it is something it cannot express.
     */
    record LinkTokenRequest(
            String clientName,
            String language,
            List<String> countryCodes,
            LinkUser user,
            List<String> products,
            String webhook) {}

    record LinkUser(String clientUserId) {}

    record AccessTokenRequest(Secret accessToken) {}

    record PublicTokenRequest(Secret publicToken) {}

    /**
     * @param cursor null on the first sync, which asks for the whole history
     * @param count how many transactions per page; Plaid's maximum is 500
     */
    record SyncRequest(Secret accessToken, String cursor, int count) {}

    // --- responses ------------------------------------------------------------------------------

    record LinkTokenResponse(String linkToken, String expiration) {}

    record ExchangeResponse(String accessToken, String itemId) {}

    record ItemResponse(Item item) {}

    record ItemRemoveResponse(String requestId) {}

    record Item(String itemId, String institutionId, String webhook) {}

    record SyncResponse(
            List<Account> accounts,
            List<Transaction> added,
            List<Transaction> modified,
            List<RemovedTransaction> removed,
            String nextCursor,
            boolean hasMore,
            String transactionsUpdateStatus) {}

    /** {@code type} is depository, credit, loan or investment; the subtype refines it. */
    record Account(String accountId, String name, String officialName, String mask, String type,
            String subtype, Balances balances) {}

    /** current is the ledger balance; available nets off what has not cleared, and is often absent. */
    record Balances(BigDecimal current, BigDecimal available, String isoCurrencyCode) {}

    record RemovedTransaction(String transactionId, String accountId) {}

    record Transaction(
            String transactionId,
            String accountId,
            BigDecimal amount,
            LocalDate date,
            LocalDate authorizedDate,
            String name,
            String merchantName,
            String merchantEntityId,
            Location location,
            PersonalFinanceCategory personalFinanceCategory,
            boolean pending,
            String pendingTransactionId) {}

    record Location(Double lat, Double lon, String city, String region) {}

    record PersonalFinanceCategory(String primary, String detailed, String confidenceLevel) {}

    record RecurringResponse(List<Stream> inflowStreams, List<Stream> outflowStreams) {}

    record Stream(
            String streamId,
            String accountId,
            String description,
            String merchantName,
            String frequency,
            String status,
            boolean isActive,
            LocalDate lastDate,
            LocalDate predictedNextDate,
            StreamAmount lastAmount,
            StreamAmount averageAmount,
            List<String> transactionIds,
            PersonalFinanceCategory personalFinanceCategory) {}

    record StreamAmount(BigDecimal amount, String isoCurrencyCode) {}

    /** Plaid's error envelope. Read for its code; never logged with the request that produced it. */
    record ErrorResponse(String errorType, String errorCode, String errorMessage, String requestId) {}

    /** What a notification carries. The body is a nudge, never an instruction to be trusted. */
    record Webhook(String webhookType, String webhookCode, String itemId) {}
}
