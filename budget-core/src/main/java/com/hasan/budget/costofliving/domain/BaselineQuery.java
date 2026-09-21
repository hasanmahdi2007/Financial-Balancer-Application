package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the resolver needs to answer "what does this cost for this person".
 *
 * <p>The city is optional and the country is not. A user whose city is not in the catalogue still
 * has a country, and the country-level estimate is what keeps them out of a dead end - so the shape
 * of this record is what makes the manual escape hatch a normal path rather than a special case.
 *
 * @param city null when the user's city is not one we hold data for. Never derived from a typed
 *     city name: that name is display-only, which is what removes fuzzy matching from the product.
 */
public record BaselineQuery(
        String userId,
        CountryCode country,
        MetroId city,
        SpendCategory category,
        IncomeQuintile quintile) {

    public BaselineQuery {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(quintile, "quintile");
    }

    public Optional<MetroId> cityIfListed() {
        return Optional.ofNullable(city);
    }

    public BaselineQuery forCategory(SpendCategory other) {
        return new BaselineQuery(userId, country, city, other, quintile);
    }
}
