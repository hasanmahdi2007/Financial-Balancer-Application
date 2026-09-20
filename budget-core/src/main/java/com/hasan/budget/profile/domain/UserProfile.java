package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import java.util.Objects;
import java.util.Optional;

/**
 * Who the user is, for planning purposes. Single person, not a household.
 *
 * <p>Location is chosen from a cascading list rather than typed, so the app always knows exactly
 * which city's data applies. When their city is not in the list they enter their own figures
 * instead, and {@code manualCityLabel} records what they called it - <strong>for display only, never
 * as a lookup key</strong>. That distinction is what removes geocoding and fuzzy matching from the
 * product: a typed name can never almost-match the wrong metro.
 *
 * @param incomeArrivesTaxed true for payroll income, which is already net of tax. This single
 *     boolean prevents the double subtraction: applying a tax rate on top of an already-net figure
 *     quietly removes another fifth of money the user actually has.
 */
public record UserProfile(
        String userId,
        CountryCode country,
        MetroId metro,
        String manualCityLabel,
        LifestyleTier lifestyleTier,
        IncomeQuintile incomeQuintile,
        boolean incomeArrivesTaxed) {

    public UserProfile {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(lifestyleTier, "lifestyleTier");
        Objects.requireNonNull(incomeQuintile, "incomeQuintile");
        if (metro == null && (manualCityLabel == null || manualCityLabel.isBlank())) {
            throw new IllegalArgumentException(
                    "a profile needs either a known metro or a manually entered city label");
        }
    }

    /** Empty when the user's city is not one we hold data for and they entered their own figures. */
    public Optional<MetroId> knownMetro() {
        return Optional.ofNullable(metro);
    }
}
