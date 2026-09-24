package com.hasan.budget.ingestion.application;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps every connected bank current without anybody asking.
 *
 * <p>This is what makes a connected bank manage itself. The provider can also notify us when new
 * transactions arrive, but only at a publicly reachable address, which a machine running this
 * locally does not have; without this schedule, data would stop at whatever the last manual refresh
 * found. With both in place the notification is simply the faster of two routes to the same sync,
 * and the sync is idempotent, so taking both is harmless.
 *
 * <p>The schedule only asks. Every sync runs on the bank executor, so this thread returns
 * immediately however many connections there are.
 */
public class BankRefreshSchedule {

    private final IngestionService ingestion;

    public BankRefreshSchedule(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @Scheduled(
            initialDelayString = "${ingestion.sync.first-refresh-after:PT2M}",
            fixedDelayString = "${ingestion.sync.refresh-every:PT4H}")
    public void refreshEveryConnection() {
        ingestion.requestSyncOfEveryConnection();
    }
}
