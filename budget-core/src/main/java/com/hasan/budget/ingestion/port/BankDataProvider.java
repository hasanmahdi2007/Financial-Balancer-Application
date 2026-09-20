package com.hasan.budget.ingestion.port;

import com.hasan.budget.ingestion.domain.SyncResult;

/**
 * Fetches read-only bank data.
 *
 * <p>A port with a real second implementation rather than an imagined one: Plaid does not operate in
 * MENA, so Lean Technologies or Tarabut Gateway is the concrete alternative for that market. Swapping
 * one for the other must not touch a single caller.
 *
 * <p>There is deliberately no method here that moves money, and that is not merely an omission.
 * Access is granted per product when the user links their bank, so requesting only transactions means
 * a payment is not something the app declines - it is something the credentials cannot express.
 *
 * <p><strong>Contract frozen by P0. Implementation belongs to packet P3.</strong>
 */
public interface BankDataProvider {

    /** Exchanges the short-lived public token from the bank's own widget for a stored access token. */
    String exchangePublicToken(String publicToken);

    /**
     * Incremental sync. The cursor is persisted by the caller, so a restart resumes rather than
     * re-importing everything from the beginning.
     */
    SyncResult sync(String accessToken, String cursorOrNull);
}
