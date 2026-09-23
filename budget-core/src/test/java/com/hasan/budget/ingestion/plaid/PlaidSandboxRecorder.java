package com.hasan.budget.ingestion.plaid;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records real Plaid Sandbox responses as test fixtures. Run by hand; never part of a build.
 *
 * <p>Every ingestion test runs against what this program saved rather than against Plaid, because
 * this machine's DNS drops intermittently and a suite that fails for connectivity reasons stops being
 * trusted. Recording from the real sandbox, rather than typing fixtures from the documentation, is the
 * point: the category mapping is built from what actually arrives, and a gym billed under
 * {@code PERSONAL_CARE} is only discoverable by looking.
 *
 * <p>Deliberately JDK-only, so it runs straight from source without a Maven classpath, from the
 * repository root:
 *
 * <pre>
 * java budget-core/src/test/java/com/hasan/budget/ingestion/plaid/PlaidSandboxRecorder.java
 * </pre>
 *
 * <p>It is not named {@code *Test}, so neither Surefire nor Failsafe will ever run it. Access tokens
 * are redacted before anything is written: sandbox tokens are harmless, but a fixture file is exactly
 * where a habit of committing real ones would start.
 */
public final class PlaidSandboxRecorder {

    private static final String BASE_URL = "https://sandbox.plaid.com";
    /** First Platypus Bank, Plaid's standard sandbox institution. */
    private static final String INSTITUTION = "ins_109508";
    private static final Path OUT = Path.of("budget-core/src/test/resources/fixtures/plaid");
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"[^\"]*\"");
    private static final Pattern NOTHING_CHANGED = Pattern.compile(
            "(?s).*\"added\"\\s*:\\s*\\[\\s*].*\"modified\"\\s*:\\s*\\[\\s*].*\"removed\"\\s*:\\s*\\[\\s*].*");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String clientId;
    private final String secret;
    private String latestCursor;

    private PlaidSandboxRecorder(Map<String, String> env) {
        this.clientId = require(env, "PLAID_CLIENT_ID");
        this.secret = require(env, "PLAID_SECRET");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = readDotEnv(Path.of(".env"));
        if (!"sandbox".equals(env.get("PLAID_ENV"))) {
            throw new IllegalStateException("refusing to record from anything but the sandbox");
        }
        String user = env.getOrDefault("PLAID_SANDBOX_USER", "user_transactions_dynamic");
        Files.createDirectories(OUT);
        new PlaidSandboxRecorder(env).record(user);
    }

    private void record(String sandboxUser) throws Exception {
        save("link-token-create.json", call("/link/token/create", """
                {"client_name":"Financial Balancer","language":"en","country_codes":["US"],
                 "user":{"client_user_id":"fixture-recorder"},"products":["transactions"]}"""));

        String publicToken = field(call("/sandbox/public_token/create", """
                {"institution_id":"%s","initial_products":["transactions"],
                 "options":{"override_username":"%s","override_password":"any-non-blank"}}"""
                .formatted(INSTITUTION, sandboxUser)), "public_token");

        String exchange = call("/item/public_token/exchange", "{\"public_token\":\"%s\"}".formatted(publicToken));
        save("item-public-token-exchange.json", exchange);
        String accessToken = field(exchange, "access_token");

        save("item-get.json", call("/item/get", tokenBody(accessToken, "")));

        String cursor = syncUntilHistoryIsReady(accessToken);

        // user_transactions_dynamic answers a refresh by posting some pending transactions and adding
        // new ones, which is the only way to record genuine modified and removed entries.
        call("/transactions/refresh", tokenBody(accessToken, ""));
        latestCursor = syncUntilSomethingChanges(accessToken, cursor, "sync-after-refresh");

        save("transactions-recurring-get.json", call("/transactions/recurring/get", tokenBody(accessToken, "")));

        recordScenarios(accessToken);
        System.out.println("fixtures written to " + OUT.toAbsolutePath());
    }

    /**
     * The default sandbox history has no rent, no savings transfer, no checking-side card payment and
     * no refund, which are exactly the cases most likely to be miscounted. The dynamic user accepts
     * custom transactions, and Plaid enriches them with its own category exactly as it would a real
     * one, so what comes back is still what arrives rather than what the documentation predicts. Only
     * the description and amount are ours.
     */
    private void recordScenarios(String accessToken) throws Exception {
        String cursor = latestCursor;
        LocalDate today = LocalDate.now();
        StringBuilder rows = new StringBuilder();
        // One rent payment only: the sandbox rejects anything posted more than 14 days ago, so it cannot
        // build the months of history a recurring rent stream needs. This records what category rent
        // actually arrives under; the stream fixture is constructed from the real response shape.
        rows.append(customTransaction(today.minusDays(5), "1450.00", "RENT PAYMENT OAKWOOD APARTMENTS")).append(',');
        rows.append(customTransaction(today.minusDays(8), "250.00", "ONLINE TRANSFER TO SAVINGS XXXXXX4421")).append(',');
        // The checking-side half of the card's "Payment Thank You" credit, same magnitude.
        rows.append(customTransaction(today.minusDays(9), "2835.80", "CHASE CREDIT CRD AUTOPAY PPD ID")).append(',');
        rows.append(customTransaction(today.minusDays(3), "-34.99", "AMAZON.COM REFUND"));

        save("sandbox-transactions-create.json", call("/sandbox/transactions/create",
                tokenBody(accessToken, ",\"transactions\":[" + rows + "]")));
        call("/transactions/refresh", tokenBody(accessToken, ""));
        syncUntilSomethingChanges(accessToken, cursor, "sync-scenarios");
        save("transactions-recurring-get-scenarios.json",
                call("/transactions/recurring/get", tokenBody(accessToken, "")));
    }

    private static String customTransaction(LocalDate date, String amount, String description) {
        return """
                {"date_transacted":"%s","date_posted":"%s","amount":%s,"description":"%s","iso_currency_code":"USD"}"""
                .formatted(date, date, amount, description);
    }

    /** The first sync after linking can legitimately return nothing while Plaid is still pulling history. */
    private String syncUntilHistoryIsReady(String accessToken) throws Exception {
        for (int attempt = 1; attempt <= 40; attempt++) {
            String first = call("/transactions/sync", syncBody(accessToken, ""));
            String status = field(first, "transactions_update_status");
            System.out.println("initial sync attempt " + attempt + ": " + status);
            if ("HISTORICAL_UPDATE_COMPLETE".equals(status)) {
                return savePages("sync-initial", accessToken, first);
            }
            Thread.sleep(5_000);
        }
        throw new IllegalStateException("sandbox never finished its historical update");
    }

    /** Returns the cursor after the changes, or the one passed in when nothing arrived. */
    private String syncUntilSomethingChanges(String accessToken, String cursor, String prefix) throws Exception {
        for (int attempt = 1; attempt <= 40; attempt++) {
            String page = call("/transactions/sync", syncBody(accessToken, cursor));
            if (!NOTHING_CHANGED.matcher(page).matches()) {
                return savePages(prefix, accessToken, page);
            }
            System.out.println("waiting for the refresh to land, attempt " + attempt);
            Thread.sleep(5_000);
        }
        System.out.println("refresh produced no changes; no " + prefix + " fixture recorded");
        return cursor;
    }

    /** Saves the page already fetched, then keeps paging while has_more is true. Returns the final cursor. */
    private String savePages(String prefix, String accessToken, String firstPage) throws Exception {
        String page = firstPage;
        for (int n = 1; ; n++) {
            save("%s-page-%d.json".formatted(prefix, n), page);
            String next = field(page, "next_cursor");
            if (!page.matches("(?s).*\"has_more\"\\s*:\\s*true.*")) {
                return next;
            }
            page = call("/transactions/sync", syncBody(accessToken, next));
        }
    }

    private String call(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("PLAID-CLIENT-ID", clientId)
                .header("PLAID-SECRET", secret)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        IOException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(path + " returned " + response.statusCode() + ": " + response.body());
                }
                return response.body();
            } catch (IOException e) {
                // This machine's DNS drops intermittently; a retry is the fix, not a diagnosis.
                last = e;
                System.out.println(path + " failed (" + e + "), retrying");
                Thread.sleep(3_000L * attempt);
            }
        }
        throw last;
    }

    private static String tokenBody(String accessToken, String extra) {
        return "{\"access_token\":\"%s\"%s}".formatted(accessToken, extra);
    }

    private static String syncBody(String accessToken, String cursor) {
        return tokenBody(accessToken, ",\"cursor\":\"%s\",\"count\":500".formatted(cursor));
    }

    private void save(String name, String body) throws IOException {
        String redacted = ACCESS_TOKEN.matcher(body).replaceAll("\"access_token\": \"access-sandbox-REDACTED\"");
        Files.writeString(OUT.resolve(name), redacted.endsWith("\n") ? redacted : redacted + "\n",
                StandardCharsets.UTF_8);
        System.out.println("saved " + name);
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("no " + name + " in response: " + json);
        }
        return m.group(1);
    }

    private static String require(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is missing from .env");
        }
        return value;
    }

    private static Map<String, String> readDotEnv(Path file) throws IOException {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            if (!line.startsWith("#") && eq > 0) {
                env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return env;
    }
}
