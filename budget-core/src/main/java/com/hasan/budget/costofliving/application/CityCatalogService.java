package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.CityListing;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.costofliving.domain.ManualLocation;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.port.CityDirectory;
import com.hasan.budget.costofliving.port.CountryBaselineSource;
import com.hasan.budget.costofliving.port.UserLocationStore;
import com.hasan.budget.profile.domain.SpendingQuestion;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The cascading location picker, and the escape hatch for everyone it does not cover.
 *
 * <p>Country, then city, then - if their city is not listed - a form that is already filled in.
 * Eleven blank boxes is where people abandon signup, so the manual path starts from the country
 * estimate and asks the user to correct what they know rather than to produce eleven numbers from
 * memory.
 */
public final class CityCatalogService {

    private static final DateTimeFormatter MONTH_AND_YEAR =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final CityDirectory directory;
    private final CountryBaselineSource countryBaselines;
    private final UserLocationStore locations;

    public CityCatalogService(
            CityDirectory directory,
            CountryBaselineSource countryBaselines,
            UserLocationStore locations) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.countryBaselines = Objects.requireNonNull(countryBaselines, "countryBaselines");
        this.locations = Objects.requireNonNull(locations, "locations");
    }

    public List<CountryProfile> countries() {
        return directory.listedCountries();
    }

    public List<CityListing> cities(CountryCode country) {
        return directory.citiesIn(country);
    }

    public Optional<CityListing> city(MetroId metro) {
        return directory.city(metro);
    }

    /**
     * Records where a user lives when their city is not one we hold data for.
     *
     * <p>Their country is what every later lookup resolves against, so this is part of the read path
     * rather than a note kept from signup: without it, the request after they finish onboarding has
     * no geography at all and their estimates would have nothing to come from.
     *
     * <p>The typed name is stored to show back to them and is never used to find anything.
     */
    public ManualLocation recordManualLocation(
            String userId, CountryCode country, String cityLabel) {
        if (directory.country(country).isEmpty()) {
            throw new IllegalArgumentException("we hold no data for country " + country.value());
        }
        ManualLocation location = new ManualLocation(userId, country, cityLabel);
        locations.save(location);
        return location;
    }

    public Optional<ManualLocation> manualLocationOf(String userId) {
        return locations.find(userId);
    }

    /**
     * The manual form, one pre-filled question per category the country has an estimate for.
     *
     * <p>Every word of it is derived from the category table and the resolved figure, never written
     * out by hand here. A question assembled from the same data that drives the behaviour cannot
     * drift out of date when a category is added, and no internal term can leak into it by accident
     * because there is no template to leak into.
     */
    public List<SpendingQuestion> manualForm(CountryCode country) {
        CountryProfile profile = directory
                .country(country)
                .orElseThrow(() -> new IllegalArgumentException(
                        "we hold no data for country " + country.value()));
        Map<SpendCategory, ResolvedBaseline> estimates = countryBaselines.countryBaselines(country);
        return Arrays.stream(SpendCategory.values())
                .filter(estimates::containsKey)
                .map(category -> question(profile, category, estimates.get(category)))
                .toList();
    }

    private SpendingQuestion question(
            CountryProfile country, SpendCategory category, ResolvedBaseline estimate) {
        return new SpendingQuestion(
                "About how much do you spend on " + category.label().toLowerCase(Locale.ENGLISH)
                        + " each month?",
                why(country),
                List.of(category),
                estimate.amount(),
                basis(country, estimate));
    }

    private String why(CountryProfile country) {
        String reason = "We do not have figures for your city yet, so we have started you from a "
                + "typical figure for " + country.name()
                + ". Changing it here only affects your own plan.";
        return country.dataNote().isBlank() ? reason : reason + " " + country.dataNote();
    }

    private String basis(CountryProfile country, ResolvedBaseline estimate) {
        return "A rough middle figure for " + country.name() + ", gathered "
                + estimate.asOf().format(MONTH_AND_YEAR)
                + ". It is a starting point rather than a measurement of your life.";
    }
}
