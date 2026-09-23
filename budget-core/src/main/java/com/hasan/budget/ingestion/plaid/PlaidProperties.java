package com.hasan.budget.ingestion.plaid;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Plaid credentials and where to send requests.
 *
 * <p>Blank by default rather than required, because the application has to start without them: the
 * integration tests of other modules boot the whole context in CI, where no bank credentials exist
 * and none should. A missing credential therefore fails at the moment somebody tries to connect a
 * bank, with a message naming the variable, rather than preventing the service from starting at all.
 *
 * @param environment sandbox or production. This is what decides the base URL, so a deployment
 *     cannot end up pointing a sandbox key at production by editing a URL somewhere.
 * @param webhookUrl where Plaid should send notifications. Optional: without a publicly reachable
 *     address there is nothing to notify, and syncing still happens when the user opens the app.
 */
@ConfigurationProperties(prefix = "ingestion.plaid")
public record PlaidProperties(
        PlaidEnvironment environment, String clientId, String secret, String clientName, String webhookUrl) {

    public PlaidProperties {
        environment = environment == null ? PlaidEnvironment.SANDBOX : environment;
        clientName = clientName == null || clientName.isBlank() ? "Financial Balancer" : clientName;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && secret != null && !secret.isBlank();
    }

    /** Which environment this points at, and the address that belongs to it. */
    public enum PlaidEnvironment {
        SANDBOX("https://sandbox.plaid.com"),
        PRODUCTION("https://production.plaid.com");

        private final String baseUrl;

        PlaidEnvironment(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String baseUrl() {
            return baseUrl;
        }
    }
}
