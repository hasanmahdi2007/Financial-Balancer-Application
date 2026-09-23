package com.hasan.budget.ingestion.plaid;

import com.hasan.budget.ingestion.domain.AccountRole;
import com.hasan.budget.ingestion.domain.AccountSnapshot;
import com.hasan.budget.ingestion.domain.NormalisedTransaction;
import com.hasan.budget.ingestion.domain.RecurringStream;
import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.port.BankDataProvider;
import com.hasan.budget.ingestion.port.BankLinkProvider;
import com.hasan.budget.ingestion.port.RecurringStreamProvider;
import com.hasan.budget.shared.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plaid, behind the three ports this module reads banks through.
 *
 * <p>All of Plaid stops here. Nothing above this class knows what a cursor looks like, that
 * categories have two levels, or that money in is negative - and a regional provider for a market
 * Plaid does not serve is a sibling of this class rather than a change to anything else.
 *
 * <p>Only the transactions product is ever requested, in {@link #createLinkToken}. That is the
 * substance behind the read-only claim: a token that was never granted auth or transfer cannot
 * initiate a payment, so the guarantee is structural rather than a promise about our own code.
 */
public class PlaidBankDataProvider implements BankDataProvider, BankLinkProvider, RecurringStreamProvider {

    /**
     * The whole of what this application asks a bank for. Written as a constant so that widening it
     * is a deliberate, reviewable line rather than an extra string in a request somewhere.
     */
    private static final List<String> GRANTED_PRODUCTS = List.of("transactions");

    private static final List<String> COUNTRIES = List.of("US");
    private static final int PAGE_SIZE = 500;

    private final PlaidClient client;
    private final PlaidTransactionMapper mapper;
    private final PlaidProperties properties;

    public PlaidBankDataProvider(PlaidClient client, PlaidTransactionMapper mapper, PlaidProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public String createLinkToken(String userId) {
        PlaidWire.LinkTokenRequest request = new PlaidWire.LinkTokenRequest(
                properties.clientName(),
                "en",
                COUNTRIES,
                new PlaidWire.LinkUser(userId),
                GRANTED_PRODUCTS,
                blankToNull(properties.webhookUrl()));
        return client.post("/link/token/create", request, PlaidWire.LinkTokenResponse.class)
                .linkToken();
    }

    @Override
    public String exchangePublicToken(String publicToken) {
        return client.post(
                        "/item/public_token/exchange",
                        new PlaidWire.PublicTokenRequest(PlaidWire.secret(publicToken)),
                        PlaidWire.ExchangeResponse.class)
                .accessToken();
    }

    @Override
    public String itemIdFor(String accessToken) {
        return client.post("/item/get", new PlaidWire.AccessTokenRequest(PlaidWire.secret(accessToken)), PlaidWire.ItemResponse.class)
                .item()
                .itemId();
    }

    @Override
    public SyncResult sync(String accessToken, String cursorOrNull) {
        PlaidWire.SyncResponse response = client.post(
                "/transactions/sync",
                new PlaidWire.SyncRequest(PlaidWire.secret(accessToken), cursorOrNull, PAGE_SIZE),
                PlaidWire.SyncResponse.class);

        Map<String, AccountRole> roles = rolesByAccount(response.accounts());
        return new SyncResult(
                normalise(response.added(), roles),
                normalise(response.modified(), roles),
                removedIds(response.removed()),
                snapshots(response.accounts()),
                response.nextCursor(),
                response.hasMore());
    }

    @Override
    public List<RecurringStream> recurringStreams(String accessToken) {
        PlaidWire.RecurringResponse response = client.post(
                "/transactions/recurring/get",
                new PlaidWire.AccessTokenRequest(PlaidWire.secret(accessToken)),
                PlaidWire.RecurringResponse.class);
        List<RecurringStream> streams = new ArrayList<>();
        addStreams(streams, response.inflowStreams(), RecurringStream.Direction.MONEY_IN);
        addStreams(streams, response.outflowStreams(), RecurringStream.Direction.MONEY_OUT);
        return List.copyOf(streams);
    }

    private void addStreams(
            List<RecurringStream> target, List<PlaidWire.Stream> source, RecurringStream.Direction direction) {
        if (source == null) {
            return;
        }
        for (PlaidWire.Stream stream : source) {
            target.add(new RecurringStream(
                    stream.streamId(),
                    stream.accountId(),
                    direction,
                    label(stream),
                    com.hasan.budget.ingestion.domain.Frequency.parse(stream.frequency()),
                    new Money(stream.lastAmount().amount()),
                    stream.lastDate(),
                    stream.predictedNextDate(),
                    isLive(stream),
                    stream.transactionIds() == null ? List.of() : stream.transactionIds()));
        }
    }

    /** The merchant where Plaid cleaned one up, because that is the name the user would recognise. */
    private static String label(PlaidWire.Stream stream) {
        return stream.merchantName() != null && !stream.merchantName().isBlank()
                ? stream.merchantName()
                : stream.description();
    }

    /**
     * A tombstoned stream is one Plaid has stopped believing in - a subscription that was cancelled,
     * or a pattern that turned out not to be one. Keeping it would leave a cancelled subscription in
     * the user's commitments indefinitely.
     */
    private static boolean isLive(PlaidWire.Stream stream) {
        return stream.isActive() && !"TOMBSTONED".equals(stream.status());
    }

    /**
     * The balances that arrived with the transactions, kept rather than discarded.
     *
     * <p>An account whose balance the bank did not report is left out entirely, because a missing
     * balance recorded as zero is money the user has that the plan cannot see.
     */
    private static List<AccountSnapshot> snapshots(List<PlaidWire.Account> accounts) {
        if (accounts == null) {
            return List.of();
        }
        return accounts.stream()
                .filter(account -> account.balances() != null && account.balances().current() != null)
                .map(account -> new AccountSnapshot(
                        account.accountId(),
                        label(account),
                        roleOf(account.type()),
                        new Money(account.balances().current()),
                        account.balances().available() == null
                                ? null
                                : new Money(account.balances().available())))
                .toList();
    }

    /** The bank's own name for the account, with the last digits where it sends them. */
    private static String label(PlaidWire.Account account) {
        String name = account.name() != null && !account.name().isBlank()
                ? account.name()
                : account.officialName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return account.mask() == null || account.mask().isBlank() ? name : name + " ••" + account.mask();
    }

    private Map<String, AccountRole> rolesByAccount(List<PlaidWire.Account> accounts) {
        Map<String, AccountRole> roles = new HashMap<>();
        if (accounts != null) {
            for (PlaidWire.Account account : accounts) {
                roles.put(account.accountId(), roleOf(account.type()));
            }
        }
        return roles;
    }

    /**
     * An unfamiliar account type counts as cash, which errs towards treating its activity as real
     * spending. The opposite default would quietly discard whatever it held.
     */
    private static AccountRole roleOf(String plaidType) {
        return switch (plaidType == null ? "" : plaidType) {
            case "credit" -> AccountRole.CARD;
            case "loan" -> AccountRole.LOAN;
            default -> AccountRole.CASH;
        };
    }

    private List<NormalisedTransaction> normalise(
            List<PlaidWire.Transaction> transactions, Map<String, AccountRole> roles) {
        if (transactions == null) {
            return List.of();
        }
        return transactions.stream()
                .map(transaction -> mapper.toTransaction(transaction, roles))
                .toList();
    }

    private static List<String> removedIds(List<PlaidWire.RemovedTransaction> removed) {
        return removed == null
                ? List.of()
                : removed.stream().map(PlaidWire.RemovedTransaction::transactionId).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
