package com.hasan.budget.ingestion.plaid;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires Plaid as the implementation of this module's provider ports.
 *
 * <p>Everything Plaid-specific is created here, so the rest of the application only ever sees the
 * ports. Replacing Plaid for a market it does not serve means writing a sibling package and changing
 * this file - not touching a service, a test or a controller.
 */
@Configuration
@EnableConfigurationProperties(PlaidProperties.class)
public class PlaidConfiguration {

    @Bean
    PfcMapping pfcMapping() {
        return PfcMapping.fromClasspath();
    }

    /**
     * The registry is optional so this module can be built in a slice test with no metrics
     * infrastructure. It falls back to a real registry rather than a no-op one, because the count of
     * unmapped categories is the signal that spending is landing in "Everything else", and a
     * counter that silently discards its increments would hide exactly what it exists to show.
     */
    @Bean
    PlaidTransactionMapper plaidTransactionMapper(PfcMapping mapping, ObjectProvider<MeterRegistry> meters) {
        return new PlaidTransactionMapper(mapping, meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    PlaidClient plaidClient(PlaidProperties properties) {
        return PlaidClient.create(RestClient.builder(), properties);
    }

    @Bean
    PlaidBankDataProvider plaidBankDataProvider(
            PlaidClient client, PlaidTransactionMapper mapper, PlaidProperties properties) {
        return new PlaidBankDataProvider(client, mapper, properties);
    }

    @Bean
    PlaidWebhookTranslator plaidWebhookTranslator() {
        return new PlaidWebhookTranslator();
    }
}
