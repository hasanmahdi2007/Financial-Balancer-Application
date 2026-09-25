package com.hasan.budget.planning.application;

import com.hasan.budget.ingestion.port.UserCountries;
import com.hasan.budget.shared.CountryCode;
import java.util.Optional;

/** The country from the user's saved profile, for deciding whether a bank can be connected. */
final class ProfileUserCountries implements UserCountries {

    private final PlanningProfileStore profiles;

    ProfileUserCountries(PlanningProfileStore profiles) {
        this.profiles = profiles;
    }

    @Override
    public Optional<CountryCode> countryOf(String userId) {
        return profiles.profile(userId).map(PlanningProfile::country);
    }
}
