package com.hasan.budget.profile.application;

import com.hasan.budget.profile.domain.ResolvedTaxRate;
import com.hasan.budget.profile.domain.TaxTreatment;
import com.hasan.budget.profile.domain.UserProfile;
import com.hasan.budget.profile.port.TaxRateLayer;
import com.hasan.budget.shared.CountryCode;
import java.util.List;
import java.util.Optional;

/**
 * Picks the tax rate that applies to one user, from an ordered list of sources.
 *
 * <p>An ordered list rather than nested conditions, so that precedence is a single readable
 * sequence and adding a source is appending an element. The user's own figure is first and wins
 * permanently: they know what they actually pay, and a seeded national estimate does not.
 *
 * <p>Layers are asked per user, which is what keeps a typed rate private. Nothing here caches a
 * rate across accounts, and nothing writes a user's answer back into a shared table - a bug that
 * would be invisible on one machine and catastrophic on a shared one.
 */
public final class TaxRateResolver {

    private final List<TaxRateLayer> layers;

    public TaxRateResolver(List<TaxRateLayer> layers) {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("a resolver with no layers can never answer");
        }
        this.layers = List.copyOf(layers);
    }

    /**
     * Empty when no layer holds a rate for that country, which is the signal to ask the user rather
     * than to guess one.
     */
    public Optional<ResolvedTaxRate> resolve(String userId, CountryCode country) {
        return layers.stream()
                .map(layer -> layer.rateFor(userId, country))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /**
     * What tax means for this user's plan.
     *
     * <p>Note which way round this is: the rate is resolved for everyone, and the profile's single
     * boolean then decides whether it funds a reserve or only explains the gross-to-net gap. An
     * employed user still gets a rate; what they do not get is a tax line.
     */
    public Optional<TaxTreatment> treatmentFor(UserProfile profile) {
        return resolve(profile.userId(), profile.country())
                .map(rate -> TaxTreatment.forProfile(profile, rate));
    }
}
