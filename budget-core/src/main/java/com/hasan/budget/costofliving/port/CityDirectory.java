package com.hasan.budget.costofliving.port;

import com.hasan.budget.costofliving.domain.CityListing;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import java.util.List;
import java.util.Optional;

/**
 * Which places exist, in the order a signup form should offer them.
 *
 * <p>Separate from {@link CostOfLivingProvider} because "which cities can I choose" and "what does
 * this city cost" are asked at different moments by different screens, and a source that can answer
 * one may not answer the other - a future official metro source would add cities without knowing
 * anything about the ones already listed.
 *
 * <p>Note what this interface cannot do: there is no lookup by name. A typed city name is display
 * text, never a key, and offering a by-name method here is exactly how fuzzy matching would creep
 * back in.
 */
public interface CityDirectory {

    /** Only countries we actually hold data for, so the dropdown can never offer a dead end. */
    List<CountryProfile> listedCountries();

    Optional<CountryProfile> country(CountryCode code);

    /** That country's cities, each carrying its own confidence and age. */
    List<CityListing> citiesIn(CountryCode code);

    Optional<CityListing> city(MetroId metro);
}
