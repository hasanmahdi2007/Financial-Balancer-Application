package com.hasan.budget.ingestion.port;

import com.hasan.budget.shared.CountryCode;
import java.util.Set;

/**
 * Starting a bank connection, and identifying the one that results.
 *
 * <p>Separate from {@link BankDataProvider} because linking and reading are genuinely different
 * capabilities: a provider can offer read access through a partner's widget, and the regional
 * providers this design keeps room for - Lean Technologies, Tarabut Gateway - each have their own
 * consent flow. Keeping them apart means an implementation that borrows somebody else's linking
 * screen implements one interface rather than stubbing half of another.
 *
 * <p>There is no method here that requests a product beyond reading transactions, and that is the
 * whole point of the interface rather than an omission from it. Access is granted per product when
 * the user links their bank, so moving money is not something this application declines - it is
 * something the resulting credential cannot express.
 */
public interface BankLinkProvider {

    /**
     * A short-lived token for the provider's own consent widget, where the user types their bank
     * credentials. Those credentials never reach this application.
     */
    String createLinkToken(String userId);

    /**
     * The countries whose banks this provider can connect to. A user living anywhere else is not
     * offered a connection at all, rather than sent into a widget that cannot find their bank.
     */
    Set<CountryCode> countriesServed();

    /**
     * The provider's identifier for the connection behind an access token.
     *
     * <p>Needed because notifications arrive naming that identifier and nothing else, so without it
     * a webhook cannot be traced back to a user.
     */
    String itemIdFor(String accessToken);

    /**
     * Tells the provider the connection is finished, so the credential stops working there as well.
     *
     * <p>Deleting our copy alone would leave a live credential at the provider that nobody can use
     * or revoke. Revoking it is what makes disconnecting mean what the user thinks it means.
     */
    void revoke(String accessToken);
}
