package com.hasan.budget.ingestion.plaid;

import com.hasan.budget.ingestion.domain.BankWebhook;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a Plaid notification and decides what, if anything, it means for us.
 *
 * <p>Every transaction notification comes out as "go and sync", including
 * {@code TRANSACTIONS_REMOVED}, which arrives carrying the identifiers it wants deleted. That list
 * is deliberately ignored. A webhook endpoint takes unauthenticated requests by nature, so acting on
 * identifiers in one would let anyone who learns an item id delete a user's spending - and deleted
 * spending makes the plan look better than reality, which is the error this whole module is built to
 * prevent. Re-syncing reaches the same end state by asking Plaid directly over the authenticated
 * connection, so nothing is lost by distrusting the body.
 *
 * <p>Unrecognised codes are reported as ignorable rather than rejected: Plaid adds notification
 * types, and an unknown one is not an error.
 */
public class PlaidWebhookTranslator {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private static final Map<String, BankWebhook.Action> ACTIONS = Map.of(
            "SYNC_UPDATES_AVAILABLE", BankWebhook.Action.SYNC_TRANSACTIONS,
            "DEFAULT_UPDATE", BankWebhook.Action.SYNC_TRANSACTIONS,
            "INITIAL_UPDATE", BankWebhook.Action.SYNC_TRANSACTIONS,
            "HISTORICAL_UPDATE", BankWebhook.Action.SYNC_TRANSACTIONS,
            "TRANSACTIONS_REMOVED", BankWebhook.Action.SYNC_TRANSACTIONS,
            "RECURRING_TRANSACTIONS_UPDATE", BankWebhook.Action.REFRESH_RECURRING);

    public BankWebhook translate(String body) {
        PlaidWire.Webhook webhook = JSON.readValue(body, PlaidWire.Webhook.class);
        if (!"TRANSACTIONS".equals(webhook.webhookType())) {
            return BankWebhook.ignored(webhook.itemId());
        }
        return new BankWebhook(
                webhook.itemId(),
                ACTIONS.getOrDefault(webhook.webhookCode(), BankWebhook.Action.IGNORE));
    }
}
