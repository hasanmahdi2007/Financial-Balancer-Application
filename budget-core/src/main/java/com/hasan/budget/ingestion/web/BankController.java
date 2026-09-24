package com.hasan.budget.ingestion.web;

import com.hasan.budget.ingestion.application.IngestionService;
import com.hasan.budget.ingestion.domain.AccountRole;
import com.hasan.budget.ingestion.domain.AccountSnapshot;
import com.hasan.budget.ingestion.domain.ConnectionStatus;
import com.hasan.budget.planning.application.NotFoundException;
import com.hasan.budget.shared.Money;
import com.hasan.budget.web.CurrentUser;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connecting a bank, seeing what it holds, and letting go of it.
 *
 * <p>The user's bank credentials never pass through here. They are typed into the provider's own
 * widget in the browser; what reaches this controller is a one-time token that widget hands back,
 * which is exchanged server-side for a credential that is encrypted before it is stored and never
 * sent back out. Nothing any route here returns can carry it: the views are built from
 * {@link ConnectionStatus} and {@link AccountSnapshot}, neither of which has a field that could.
 *
 * <p>Every route is scoped by {@link CurrentUser}, so no request can name another person's bank.
 */
@RestController
@RequestMapping("/api/v1/bank")
class BankController {

    private final IngestionService ingestion;

    BankController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    record LinkTokenView(String linkToken) {}

    record ConnectRequest(String publicToken) {}

    record ConnectionView(long id, Instant connectedAt, Instant lastUpdated, String status) {}

    /**
     * @param balance for a card or a loan, what is owed rather than what is held - {@code meaning}
     *     says which, so the number is never shown without it
     * @param available null when the bank does not report one
     */
    record AccountView(String name, String kind, String balance, String meaning, String available) {}

    record BankView(boolean connected, List<ConnectionView> connections, List<AccountView> accounts, String explanation) {}

    record RefreshView(int banks) {}

    @GetMapping
    BankView bank(@CurrentUser String userId) {
        List<ConnectionView> connections = ingestion.connectionsFor(userId).stream()
                .map(BankController::view)
                .toList();
        // Money the user holds first, then what they owe: the order someone reads a bank statement in.
        List<AccountView> accounts = ingestion.accountsFor(userId).stream()
                .sorted(Comparator.comparing(AccountSnapshot::role)
                        .thenComparing(account -> account.label() == null ? "" : account.label()))
                .map(BankController::view)
                .toList();
        return new BankView(!connections.isEmpty(), connections, accounts, EXPLANATION);
    }

    /** A short-lived token that opens the provider's connection widget for this user. */
    @PostMapping("/link-token")
    LinkTokenView linkToken(@CurrentUser String userId) {
        return new LinkTokenView(ingestion.startLinking(userId));
    }

    /**
     * Finishes connecting. Answers as soon as the connection is recorded; the import runs in the
     * background, which is why this is 202 and why the view says it is still importing.
     */
    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.ACCEPTED)
    BankView connect(@CurrentUser String userId, @RequestBody ConnectRequest request) {
        if (request == null || request.publicToken() == null || request.publicToken().isBlank()) {
            throw new IllegalArgumentException("Connect your bank through the bank window first.");
        }
        ingestion.connect(userId, request.publicToken().strip());
        return bank(userId);
    }

    /** Asks every one of this user's banks for anything new. Returns before any of them answers. */
    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RefreshView refresh(@CurrentUser String userId) {
        return new RefreshView(ingestion.requestSyncFor(userId));
    }

    @DeleteMapping("/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@CurrentUser String userId, @PathVariable long id) {
        if (!ingestion.disconnect(userId, id)) {
            throw new NotFoundException("We could not find that bank connection.");
        }
    }

    private static final String EXPLANATION =
            "We can only read your transactions and balances. Nothing here can move money, and your bank "
                    + "login is typed into your bank's own window, never into ours. Your accounts are checked "
                    + "for anything new every few hours.";

    private static ConnectionView view(ConnectionStatus status) {
        return new ConnectionView(
                status.id(),
                status.connectedAt(),
                status.lastSyncedAt(),
                status.hasImported() ? "Up to date" : "Importing your transactions. This takes a minute or two.");
    }

    private static AccountView view(AccountSnapshot account) {
        return new AccountView(
                account.label() == null ? "Account" : account.label(),
                kindOf(account.role()),
                amount(account.current()),
                meaningOf(account.role()),
                account.available() == null ? null : amount(account.available()));
    }

    private static String kindOf(AccountRole role) {
        return switch (role) {
            case CASH -> "Bank account";
            case CARD -> "Credit card";
            case LOAN -> "Loan";
        };
    }

    private static String meaningOf(AccountRole role) {
        return switch (role) {
            case CASH -> "In the account";
            case CARD -> "Owed on the card";
            case LOAN -> "Still owed";
        };
    }

    private static String amount(Money money) {
        return money.amount().toPlainString();
    }
}
