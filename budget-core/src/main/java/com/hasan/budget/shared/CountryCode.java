package com.hasan.budget.shared;

import java.util.Objects;

/**
 * An ISO 3166-1 alpha-2 country code, such as {@code US} or {@code LB}.
 *
 * <p>Lives in {@code shared} because both {@code costofliving} and {@code profile} need it, and
 * shared is the one package that may be depended on from anywhere. Typed rather than a bare string
 * so a country code cannot be passed where a city was meant.
 */
public record CountryCode(String value) {

    public CountryCode {
        Objects.requireNonNull(value, "value");
        if (value.length() != 2) {
            throw new IllegalArgumentException("expected a 2-letter ISO country code but got " + value);
        }
        value = value.toUpperCase();
    }

    public static final CountryCode US = new CountryCode("US");
    public static final CountryCode LEBANON = new CountryCode("LB");
}
