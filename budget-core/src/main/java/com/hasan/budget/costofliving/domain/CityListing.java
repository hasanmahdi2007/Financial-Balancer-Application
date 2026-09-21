package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One entry in the city dropdown, carrying enough provenance to be honest about itself.
 *
 * <p>Confidence and {@code asOf} travel with the listing rather than being fetched afterwards,
 * because the moment a user picks a city is the moment they deserve to know whether the numbers
 * behind it are a month old or two years old.
 *
 * @param metro the only lookup key there is. A city name the user types never produces one of these.
 * @param asOf the oldest figure held for this city, which is the honest age of the whole listing.
 */
public record CityListing(
        MetroId metro,
        CountryCode country,
        String displayName,
        String admin1,
        Confidence confidence,
        LocalDate asOf) {

    public CityListing {
        Objects.requireNonNull(metro, "metro");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(asOf, "asOf");
    }
}
