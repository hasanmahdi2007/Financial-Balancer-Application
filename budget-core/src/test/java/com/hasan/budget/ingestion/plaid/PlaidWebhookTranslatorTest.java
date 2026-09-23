package com.hasan.budget.ingestion.plaid;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.ingestion.domain.BankWebhook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What arrives at the one door that is not behind the gateway, and how little of it is believed. */
class PlaidWebhookTranslatorTest {

    private final PlaidWebhookTranslator translator = new PlaidWebhookTranslator();

    @Test
    @DisplayName("an update notification asks for a sync")
    void theOrdinaryCase() {
        BankWebhook webhook =
                translator.translate(RecordedSandbox.fixture("constructed/webhook-sync-updates-available.json"));

        assertThat(webhook.action()).isEqualTo(BankWebhook.Action.SYNC_TRANSACTIONS);
        assertThat(webhook.providerItemId()).isNotBlank();
    }

    @Test
    @DisplayName("a removal notification also just asks for a sync, and its list is ignored")
    void theListOfIdentifiersIsNeverActedOn() {
        String body = RecordedSandbox.fixture("constructed/webhook-transactions-removed.json");
        assertThat(body).contains("removed_transactions");

        BankWebhook webhook = translator.translate(body);

        // Anyone can post to a webhook endpoint. Acting on identifiers in an unauthenticated body
        // would let a stranger delete a user's spending - and less spending flatters the plan.
        // Asking Plaid over the authenticated connection reaches the same end state safely.
        assertThat(webhook.action()).isEqualTo(BankWebhook.Action.SYNC_TRANSACTIONS);
    }

    @Test
    @DisplayName("a recurring update re-reads streams without re-reading transactions")
    void recurringUpdatesAreTheirOwnAction() {
        BankWebhook webhook = translator.translate(
                RecordedSandbox.fixture("constructed/webhook-recurring-transactions-update.json"));

        assertThat(webhook.action()).isEqualTo(BankWebhook.Action.REFRESH_RECURRING);
    }

    @Test
    @DisplayName("notifications about anything but transactions are not this module's business")
    void otherWebhookTypesAreIgnored() {
        BankWebhook webhook = translator.translate(RecordedSandbox.fixture("constructed/webhook-item-error.json"));

        assertThat(webhook.action()).isEqualTo(BankWebhook.Action.IGNORE);
        assertThat(webhook.providerItemId()).isNotBlank();
    }

    @Test
    @DisplayName("a notification type nobody has seen before is ignored rather than rejected")
    void anUnknownCodeIsNotAnError() {
        BankWebhook webhook = translator.translate(
                """
                {"webhook_type":"TRANSACTIONS","webhook_code":"SOMETHING_NEW","item_id":"item-1"}""");

        assertThat(webhook.action()).isEqualTo(BankWebhook.Action.IGNORE);
        assertThat(webhook.providerItemId()).isEqualTo("item-1");
    }

    @Test
    @DisplayName("the older update codes still mean sync, for items not on the cursor API")
    void legacyCodesAreUnderstood() {
        assertThat(translate("DEFAULT_UPDATE")).isEqualTo(BankWebhook.Action.SYNC_TRANSACTIONS);
        assertThat(translate("INITIAL_UPDATE")).isEqualTo(BankWebhook.Action.SYNC_TRANSACTIONS);
        assertThat(translate("HISTORICAL_UPDATE")).isEqualTo(BankWebhook.Action.SYNC_TRANSACTIONS);
    }

    private BankWebhook.Action translate(String code) {
        return translator
                .translate("""
                        {"webhook_type":"TRANSACTIONS","webhook_code":"%s","item_id":"item-1"}"""
                        .formatted(code))
                .action();
    }
}
