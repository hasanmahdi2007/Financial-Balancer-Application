package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.ManualLocation;
import com.hasan.budget.costofliving.port.UserLocationStore;
import com.hasan.budget.shared.CountryCode;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The Postgres adapter for users whose city is not one we hold data for. */
@Repository
class JpaUserLocationStore implements UserLocationStore {

    private final UserManualLocationRepository locations;

    JpaUserLocationStore(UserManualLocationRepository locations) {
        this.locations = locations;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ManualLocation> find(String userId) {
        return locations.findById(userId)
                .map(entity -> new ManualLocation(
                        entity.userId(), new CountryCode(entity.countryCode()), entity.cityLabel()));
    }

    @Override
    @Transactional
    public void save(ManualLocation location) {
        locations.save(new UserManualLocationEntity(
                location.userId(), location.country().value(), location.cityLabel()));
    }
}
