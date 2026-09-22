package com.hasan.budget.ingestion.plaid;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hasan.budget.ingestion.domain.SyncInterrupted;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every call this application makes to Plaid goes through here.
 *
 * <p>One place to hold three things that are easy to get subtly wrong in each call site instead:
 * credentials travel as headers rather than in the body, errors become a typed exception that cannot
 * carry the request that produced it, and the mapping between Plaid's snake_case and this codebase's
 * naming is configured once rather than annotated onto sixty record components.
 *
 * <p>Built from a {@link RestClient.Builder} the caller supplies, so a test can hand it a builder
 * bound to a mock server and exercise the real request-building and response-parsing code without a
 * network. Every test in this module does exactly that.
 */
public class PlaidClient {

    private final RestClient http;
    private final PlaidProperties properties;

    PlaidClient(RestClient.Builder builder, PlaidProperties properties) {
        this.properties = properties;
        this.http = builder.baseUrl(properties.environment().baseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .configureMessageConverters(converters ->
                        converters.withJsonConverter(new JacksonJsonHttpMessageConverter(JSON)))
                .build();
    }

    public static PlaidClient create(RestClient.Builder builder, PlaidProperties properties) {
        return new PlaidClient(builder, properties);
    }

    /**
     * Plaid speaks snake_case; nulls are omitted so that an absent cursor asks for a full history
     * rather than being sent as the string "null", and unknown fields are tolerated because Plaid
     * adds them without warning and a new field is never a reason to stop importing someone's money.
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    <T> T post(String path, Object request, Class<T> responseType) {
        requireCredentials();
        return http.post()
                .uri(path)
                .header("PLAID-CLIENT-ID", properties.clientId())
                .header("PLAID-SECRET", properties.secret())
                .body(request)
                .exchange((httpRequest, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw translate(path, new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    }
                    return response.bodyTo(responseType);
                });
    }

    /**
     * Plaid reports a changed dataset mid-pagination as an ordinary error, but it is the one that
     * must not be retried from where it stopped, so it becomes its own type here rather than being
     * discovered by string-matching an error code further up.
     */
    private RuntimeException translate(String path, String body) {
        PlaidWire.ErrorResponse error = readError(body);
        if ("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION".equals(error.errorCode())) {
            return new SyncInterrupted("the bank's data changed while it was being paged; starting again");
        }
        return new PlaidApiException(path, error.errorType(), error.errorCode(), error.requestId());
    }

    private PlaidWire.ErrorResponse readError(String body) {
        try {
            return JSON.readValue(body, PlaidWire.ErrorResponse.class);
        } catch (RuntimeException e) {
            // An error body we cannot parse is still an error; what it must not become is the body
            // itself in a log line, since the request that produced it carried an access token.
            return new PlaidWire.ErrorResponse("UNPARSEABLE", "UNPARSEABLE", "", "");
        }
    }

    private void requireCredentials() {
        if (!properties.isConfigured()) {
            throw new IllegalStateException(
                    "PLAID_CLIENT_ID and PLAID_SECRET are not set, so no bank can be connected. "
                            + "Add them to .env; they are free from https://dashboard.plaid.com.");
        }
    }
}
