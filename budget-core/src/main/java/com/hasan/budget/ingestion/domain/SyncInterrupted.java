package com.hasan.budget.ingestion.domain;

/**
 * The provider's data changed while we were part-way through paging it.
 *
 * <p>Not a failure, and specifically not one to retry from where it stopped: the pages already read
 * describe a state of the world that no longer exists, and stitching them to later pages would
 * produce a ledger that never existed either. The only correct response is to start again from the
 * cursor the sync began at, which is why this is a type of its own rather than a generic error.
 */
public class SyncInterrupted extends RuntimeException {

    public SyncInterrupted(String message) {
        super(message);
    }
}
