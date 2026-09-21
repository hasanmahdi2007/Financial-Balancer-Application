package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.CountryCode;
import java.util.Objects;

/**
 * Where a user says they live, when we hold no data for their city.
 *
 * <p>The country is a real key and the city is not. {@code cityLabel} exists so the interface can
 * say "Bcharre" back to the person who typed it, and for nothing else: the moment anything resolves
 * a metro from it, a misspelling silently selects another city's cost of living and every number
 * downstream is wrong with nothing failing. Keeping the two in one record, with only one of them
 * typed, is what makes that distinction hard to lose.
 */
public record ManualLocation(String userId, CountryCode country, String cityLabel) {

    public ManualLocation {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(cityLabel, "cityLabel");
        if (cityLabel.isBlank()) {
            throw new IllegalArgumentException("a manual location must say where the user lives");
        }
    }
}
