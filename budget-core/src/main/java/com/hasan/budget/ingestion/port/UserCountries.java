package com.hasan.budget.ingestion.port;

import com.hasan.budget.shared.CountryCode;
import java.util.Optional;

/**
 * Which country a user lives in, as far as their profile says.
 *
 * <p>A port because the profile belongs to another module, which already depends on this one for
 * bank spending. Asking it directly would make each module need the other before either could exist.
 * Here the ingestion module states the one fact it needs and the profile's owner supplies it.
 */
public interface UserCountries {

    /** Empty until the user has said where they live. */
    Optional<CountryCode> countryOf(String userId);
}
