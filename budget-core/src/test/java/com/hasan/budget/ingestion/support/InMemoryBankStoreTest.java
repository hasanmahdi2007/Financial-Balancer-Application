package com.hasan.budget.ingestion.support;

import com.hasan.budget.ingestion.port.BankConnections;
import com.hasan.budget.ingestion.port.BankLedger;
import java.util.concurrent.atomic.AtomicInteger;

/** The in-memory store, held to the same contract as Postgres. */
class InMemoryBankStoreTest extends BankLedgerContract {

    private static final AtomicInteger USERS = new AtomicInteger();

    private final InMemoryBankStore store = new InMemoryBankStore();

    @Override
    protected BankConnections connections() {
        return store;
    }

    @Override
    protected BankLedger ledger() {
        return store;
    }

    @Override
    protected String someUserId() {
        return "user-" + USERS.incrementAndGet();
    }
}
