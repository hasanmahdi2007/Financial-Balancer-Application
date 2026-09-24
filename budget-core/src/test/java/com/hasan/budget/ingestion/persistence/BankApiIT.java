package com.hasan.budget.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hasan.budget.ingestion.domain.AccountRole;
import com.hasan.budget.ingestion.domain.AccountSnapshot;
import com.hasan.budget.ingestion.domain.Classification;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.ingestion.domain.RecurringStream;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.port.BankDataProvider;
import com.hasan.budget.ingestion.port.BankLinkProvider;
import com.hasan.budget.ingestion.port.RecurringStreamProvider;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.web.CurrentUserArgumentResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Connecting a bank over HTTP, against the real schema, with the provider replaced by one in memory.
 *
 * <p>What can be wrong here and nowhere else: the routes, the serialised shape, the SQL behind
 * listing and disconnecting, and - the one that matters most - whether any of it is scoped to the
 * caller. The provider is faked because no test may touch the network; everything between the
 * request and the database is real.
 *
 * <p>Its own container, started in a static initialiser, for the reason the planning and
 * cost-of-living fixtures give: a container stopped by one class while Spring keeps its context
 * cached for the next fails in a way that looks nothing like its cause.
 */
@SpringBootTest(properties = {
    // A throwaway key: 32 zero bytes. It only has to be well-formed for tokens to be sealed and opened.
    "ingestion.token-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "ingestion.sync.scheduled=false"
})
@AutoConfigureMockMvc
class BankApiIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryProvider bank;

    // Fresh ids per test: the database is shared across the class.
    private final String ana = "ana-" + UUID.randomUUID();
    private final String ben = "ben-" + UUID.randomUUID();

    @BeforeEach
    void aWorkingBank() {
        bank.failing = false;
    }

    @Test
    @DisplayName("before anything is connected the page says so, and explains what connecting means")
    void nothingConnectedYet() throws Exception {
        mockMvc.perform(as(ana, get("/api/v1/bank")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.connections").isEmpty())
                .andExpect(jsonPath("$.accounts").isEmpty())
                .andExpect(jsonPath("$.explanation").value(containsString("Nothing here can move money")));
    }

    @Test
    @DisplayName("a link token opens the bank's own window for this user")
    void aLinkTokenIsIssued() throws Exception {
        mockMvc.perform(as(ana, post("/api/v1/bank/link-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkToken").value("link-sandbox-for-" + ana));
    }

    @Test
    @DisplayName("connecting imports in the background, then shows the accounts with what each figure means")
    void connectingShowsTheAccounts() throws Exception {
        mockMvc.perform(as(ana, post("/api/v1/bank/connections")).content("""
                        {"publicToken":"public-%s"}""".formatted(ana)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.connections.length()").value(1));

        awaitImportFor(ana);

        mockMvc.perform(as(ana, get("/api/v1/bank")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections[0].status").value("Up to date"))
                .andExpect(jsonPath("$.connections[0].lastUpdated").exists())
                .andExpect(jsonPath("$.accounts[0].name").value("Plaid Checking ••0000"))
                .andExpect(jsonPath("$.accounts[0].kind").value("Bank account"))
                .andExpect(jsonPath("$.accounts[0].balance").value("110.00"))
                .andExpect(jsonPath("$.accounts[0].meaning").value("In the account"))
                .andExpect(jsonPath("$.accounts[1].kind").value("Credit card"))
                .andExpect(jsonPath("$.accounts[1].meaning").value("Owed on the card"))
                .andExpect(jsonPath("$.accounts[1].available").doesNotExist());
    }

    @Test
    @DisplayName("no response ever carries the bank credential")
    void theCredentialNeverLeaves() throws Exception {
        mockMvc.perform(as(ana, post("/api/v1/bank/connections")).content("""
                        {"publicToken":"public-%s"}""".formatted(ana)))
                .andExpect(content().string(not(containsString("access-"))));
        awaitImportFor(ana);

        mockMvc.perform(as(ana, get("/api/v1/bank")))
                .andExpect(content().string(not(containsString("access-"))))
                .andExpect(content().string(not(containsString("v1:"))));
    }

    @Test
    @DisplayName("one user never sees, refreshes or disconnects another's bank")
    void everythingIsScopedToTheCaller() throws Exception {
        long anasBank = connectAndImport(ana);

        mockMvc.perform(as(ben, get("/api/v1/bank")))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.accounts").isEmpty());
        mockMvc.perform(as(ben, post("/api/v1/bank/refresh")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.banks").value(0));
        mockMvc.perform(as(ben, delete("/api/v1/bank/connections/" + anasBank)))
                .andExpect(status().isNotFound());

        mockMvc.perform(as(ana, get("/api/v1/bank")))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.accounts.length()").value(2));
    }

    @Test
    @DisplayName("disconnecting revokes the credential and removes the bank's accounts")
    void disconnecting() throws Exception {
        long connectionId = connectAndImport(ana);

        mockMvc.perform(as(ana, delete("/api/v1/bank/connections/" + connectionId)))
                .andExpect(status().isNoContent());

        assertThat(bank.revoked).contains("access-public-" + ana);
        mockMvc.perform(as(ana, get("/api/v1/bank")))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.accounts").isEmpty());
    }

    @Test
    @DisplayName("refreshing asks each of the caller's banks and answers before any of them does")
    void refreshing() throws Exception {
        connectAndImport(ana);

        mockMvc.perform(as(ana, post("/api/v1/bank/refresh")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.banks").value(1));
    }

    @Test
    @DisplayName("a bank that cannot be reached is explained in a sentence, never in the provider's words")
    void anUnreachableBankExplainsItself() throws Exception {
        bank.failing = true;

        mockMvc.perform(as(ana, post("/api/v1/bank/link-token")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail")
                        .value("We could not start connecting to your bank just now. Try again in a few minutes."))
                .andExpect(content().string(not(containsString("INVALID_API_KEYS"))));
    }

    @Test
    @DisplayName("connecting without the bank window's token is refused with a reason")
    void connectingNeedsAToken() throws Exception {
        mockMvc.perform(as(ana, post("/api/v1/bank/connections")).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Connect your bank through the bank window first."));
    }

    @Test
    @DisplayName("nobody who is not signed in reaches any of it")
    void signedOutIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/bank")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/bank/link-token")).andExpect(status().isUnauthorized());
    }

    private long connectAndImport(String userId) throws Exception {
        mockMvc.perform(as(userId, post("/api/v1/bank/connections")).content("""
                        {"publicToken":"public-%s"}""".formatted(userId)))
                .andExpect(status().isAccepted());
        awaitImportFor(userId);
        String body = mockMvc.perform(as(userId, get("/api/v1/bank")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(body.replaceAll("(?s).*\"connections\":\\[\\{\"id\":(\\d+).*", "$1"));
    }

    /** The import runs on the bank executor, so it is waited for rather than assumed. */
    private void awaitImportFor(String userId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String body = mockMvc.perform(as(userId, get("/api/v1/bank")))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            if (body.contains("\"Up to date\"")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the import for " + userId + " never finished");
    }

    private static MockHttpServletRequestBuilder as(String userId, MockHttpServletRequestBuilder request) {
        return request.header(CurrentUserArgumentResolver.USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    @TestConfiguration
    static class ProviderInMemory {

        @Bean
        @Primary
        InMemoryProvider inMemoryProvider() {
            return new InMemoryProvider();
        }
    }

    /**
     * A bank that answers from memory. Each public token becomes its own connection, so every test
     * can hold one without colliding with another's.
     */
    static class InMemoryProvider implements BankDataProvider, BankLinkProvider, RecurringStreamProvider {

        final List<String> revoked = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public String createLinkToken(String userId) {
            if (failing) {
                throw new IllegalStateException("Plaid /link/token/create failed: INVALID_API_KEYS");
            }
            return "link-sandbox-for-" + userId;
        }

        @Override
        public String exchangePublicToken(String publicToken) {
            return "access-" + publicToken;
        }

        @Override
        public String itemIdFor(String accessToken) {
            return "item-" + accessToken;
        }

        @Override
        public void revoke(String accessToken) {
            revoked.add(accessToken);
        }

        @Override
        public SyncResult sync(String accessToken, String cursorOrNull) {
            NormalisedTransaction coffee = new NormalisedTransaction(
                    "t-coffee",
                    "acc-checking",
                    LocalDate.of(2026, 9, 14),
                    Money.of("4.33"),
                    "Starbucks",
                    null,
                    null,
                    null,
                    Classification.spend(SpendCategory.DINING_OUT),
                    false);
            return new SyncResult(
                    List.of(coffee),
                    List.of(),
                    List.of(),
                    List.of(
                            new AccountSnapshot(
                                    "acc-checking", "Plaid Checking ••0000", AccountRole.CASH,
                                    Money.of("110.00"), Money.of("100.00")),
                            new AccountSnapshot(
                                    "acc-card", "Plaid Credit Card ••3333", AccountRole.CARD, Money.of("410.00"), null)),
                    "cursor-1",
                    false);
        }

        @Override
        public List<RecurringStream> recurringStreams(String accessToken) {
            return List.of();
        }
    }
}
