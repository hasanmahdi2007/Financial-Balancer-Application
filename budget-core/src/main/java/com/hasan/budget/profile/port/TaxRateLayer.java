package com.hasan.budget.profile.port;

import com.hasan.budget.profile.domain.ResolvedTaxRate;
import com.hasan.budget.shared.CountryCode;
import java.util.Optional;

/**
 * One rung of the tax-rate resolution chain.
 *
 * <p>The same shape as the cost-of-living chain, and for the same reason: adding a source is
 * appending a layer, not editing a condition. Precedence is the order the layers are given to the
 * resolver, so it is stated in one place rather than implied by a nest of ifs spread across the
 * chain.
 *
 * <p>Returns empty rather than a zero when this layer holds nothing, so that a later layer can
 * answer. A layer that returned {@code 0%} instead would silently end the chain and switch off a
 * freelancer's entire tax reserve.
 */
public interface TaxRateLayer {

    Optional<ResolvedTaxRate> rateFor(String userId, CountryCode country);
}
