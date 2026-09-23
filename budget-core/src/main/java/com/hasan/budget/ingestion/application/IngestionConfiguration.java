package com.hasan.budget.ingestion.application;

import com.hasan.budget.ingestion.port.BankConnections;
import com.hasan.budget.ingestion.port.BankDataProvider;
import com.hasan.budget.ingestion.port.BankLedger;
import com.hasan.budget.ingestion.port.BankLinkProvider;
import com.hasan.budget.ingestion.port.RecurringStreamProvider;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the ingestion module, and puts the one decision that matters in a visible place.
 *
 * <p>That decision is the executor. Bank imports run on a small, bounded pool of their own rather
 * than on whatever thread pool happens to be around: the provider rate-limits per item, the work is
 * slow and entirely I/O, and giving it a pool of its own means a burst of notifications cannot
 * starve anything else in the service.
 */
@Configuration
public class IngestionConfiguration {

    @Bean
    AccessTokenCipher accessTokenCipher(@Value("${ingestion.token-encryption-key:}") String key) {
        return new AccessTokenCipher(key);
    }

    /**
     * Small on purpose. The queue is bounded too, so that a flood of notifications is rejected
     * loudly rather than accumulating in memory until the service falls over.
     */
    @Bean(name = "bankSyncExecutor")
    Executor bankSyncExecutor(@Value("${ingestion.sync.threads:2}") int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("bank-sync-");
        executor.initialize();
        return executor;
    }

    @Bean
    IngestionService ingestionService(
            BankLinkProvider links,
            BankDataProvider banks,
            RecurringStreamProvider streams,
            BankConnections connections,
            BankLedger ledger,
            AccessTokenCipher cipher,
            @Qualifier("bankSyncExecutor") Executor bankSyncExecutor) {
        return new IngestionService(links, banks, streams, connections, ledger, cipher, bankSyncExecutor);
    }

    @Bean
    LedgerMerchantPrices observedMerchantPrices(IngestionService ingestion) {
        return new LedgerMerchantPrices(ingestion);
    }
}
