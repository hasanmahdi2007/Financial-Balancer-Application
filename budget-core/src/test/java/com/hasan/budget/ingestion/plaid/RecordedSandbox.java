package com.hasan.budget.ingestion.plaid;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The recorded Plaid Sandbox, served back to the real adapter without a network.
 *
 * <p>Tests run against the bytes Plaid actually sent, through the real request building, the real
 * JSON parsing and the real category mapping. A hand-written stub of the provider would test the
 * stub: it would agree with whatever the mapping believes, and every surprise this module exists to
 * survive - payroll tagged as a restaurant, a card payment tagged as salary - is precisely the kind
 * of thing a stub author would not think to reproduce.
 *
 * <p>The recorded pages are chained by their own cursors, so asking for the cursor a page returned
 * yields the page that really followed it, and asking again at the end yields an empty page - the
 * same shape a caller meets when a bank has nothing new.
 */
public final class RecordedSandbox {

    public static final String BASE_URL = "https://sandbox.plaid.com";

    private static final Pattern NEXT_CURSOR = Pattern.compile("\"next_cursor\"\\s*:\\s*\"([^\"]*)\"");

    private final MockRestServiceServer server;
    private final PlaidBankDataProvider provider;
    private final MeterRegistry meters;

    private RecordedSandbox(MockRestServiceServer server, PlaidBankDataProvider provider, MeterRegistry meters) {
        this.server = server;
        this.provider = provider;
        this.meters = meters;
    }

    /** A sandbox serving the whole recorded history: first import, refresh, then the prompted rows. */
    public static RecordedSandbox withRecordedHistory() {
        return serving(fixture("sync-initial-page-1.json"),
                fixture("sync-after-refresh-page-1.json"),
                fixture("sync-scenarios-page-1.json"));
    }

    /** A sandbox serving only the pages given, in order, chained by their recorded cursors. */
    public static RecordedSandbox serving(String... pages) {
        return servingRecurring(fixture("transactions-recurring-get-scenarios.json"), pages);
    }

    /**
     * As {@link #serving}, with a chosen answer for detected streams - the constructed one carrying
     * a rent stream, for instance, which the sandbox itself cannot produce.
     */
    public static RecordedSandbox servingRecurring(String recurring, String... pages) {
        PlaidProperties properties = credentials();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true)
                .build();

        String cursor = null;
        for (String page : pages) {
            expectSync(server, cursor, page);
            cursor = nextCursor(page);
        }
        expectSync(server, cursor, emptyPageAt(cursor));

        expectPost(server, "/link/token/create", fixture("link-token-create.json"));
        expectPost(server, "/item/public_token/exchange", fixture("item-public-token-exchange.json"));
        expectPost(server, "/item/get", fixture("item-get.json"));
        expectPost(server, "/transactions/recurring/get", recurring);

        MeterRegistry meters = new SimpleMeterRegistry();
        PlaidClient client = PlaidClient.create(builder, properties);
        PlaidBankDataProvider provider = new PlaidBankDataProvider(
                client, new PlaidTransactionMapper(PfcMapping.fromClasspath(), meters), properties);
        return new RecordedSandbox(server, provider, meters);
    }

    /**
     * The exact JSON this application sends when it asks for consent to a bank.
     *
     * <p>Returned as the raw body rather than as an object, because the claim being tested is about
     * what goes over the wire. An assertion against a request object would pass even if a field were
     * being added somewhere between the object and the socket.
     */
    public static String linkTokenRequestBody() {
        PlaidProperties properties = credentials();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        StringBuilder captured = new StringBuilder();
        server.expect(requestTo(BASE_URL + "/link/token/create"))
                .andExpect(request -> captured.append(
                        new String(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsBytes(), StandardCharsets.UTF_8)))
                .andRespond(withSuccess(fixture("link-token-create.json"), MediaType.APPLICATION_JSON));

        PlaidClient client = PlaidClient.create(builder, properties);
        new PlaidBankDataProvider(
                        client,
                        new PlaidTransactionMapper(PfcMapping.fromClasspath(), new SimpleMeterRegistry()),
                        properties)
                .createLinkToken("user-1");
        return captured.toString();
    }

    private static void expectSync(MockRestServiceServer server, String cursor, String page) {
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE_URL + "/transactions/sync"))
                // A first sync sends no cursor at all rather than an empty one, which is what asks
                // the provider for the whole history.
                .andExpect(cursor == null
                        ? jsonPath("$.cursor").doesNotExist()
                        : jsonPath("$.cursor", Matchers.is(cursor)))
                .andRespond(withSuccess(page, MediaType.APPLICATION_JSON));
    }

    private static void expectPost(MockRestServiceServer server, String path, String body) {
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE_URL + path))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String emptyPageAt(String cursor) {
        return """
                {"accounts":[],"added":[],"modified":[],"removed":[],
                 "next_cursor":"%s","has_more":false,"request_id":"nothing-new"}"""
                .formatted(cursor);
    }

    public PlaidBankDataProvider provider() {
        return provider;
    }

    /** Where the count of unmapped categories lands, so a test can assert it stayed at zero. */
    public MeterRegistry meters() {
        return meters;
    }

    public double unmappedCategoryCount() {
        return meters.find("ingestion.plaid.unmapped_category").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    public static String nextCursor(String page) {
        Matcher matcher = NEXT_CURSOR.matcher(page);
        if (!matcher.find()) {
            throw new IllegalArgumentException("that fixture has no next_cursor");
        }
        return matcher.group(1);
    }

    /** Reads a committed fixture. {@code constructed/...} for the ones the sandbox could not produce. */
    public static String fixture(String name) {
        String path = "/fixtures/plaid/" + name;
        try (InputStream stream = RecordedSandbox.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("no such fixture: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Credentials that are obviously not real. The recorded responses are already redacted, and a
     * test that needed a working key would be a test that reached the network.
     */
    private static PlaidProperties credentials() {
        return new PlaidProperties(
                PlaidProperties.PlaidEnvironment.SANDBOX, "test-client-id", "test-secret", null, null);
    }
}
