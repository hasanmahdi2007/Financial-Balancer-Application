package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.util.Map;
import java.util.Optional;

/**
 * What planning needs to know about places, and nothing more.
 *
 * <p>Narrow on purpose. The plan needs a country's name, a city's name, a way to record a city we do
 * not list, and one user's resolved figures; it does not need the resolver's layers or the catalogue's
 * wording. Keeping the seam this small is what lets every planning test run without a database, and
 * what keeps a change to how cost of living is resolved from reaching into planning.
 */
public interface Places {

    Optional<String> countryName(CountryCode country);

    /** A listed city, if it exists and is in that country. */
    Optional<City> city(CountryCode country, MetroId city);

    /** Records where a user lives when their city is not listed. The name is display text only. */
    void recordCityNotListed(String userId, CountryCode country, String cityName);

    /** Every category's figure for this user, each still carrying where it came from. */
    Map<SpendCategory, ResolvedBaseline> baselinesFor(PlanningProfile profile);

    /** @param basis and the rest describe the city's own figures, in a person's words */
    record City(MetroId id, String name, String basis, String explanation, java.time.LocalDate gathered) {}
}
