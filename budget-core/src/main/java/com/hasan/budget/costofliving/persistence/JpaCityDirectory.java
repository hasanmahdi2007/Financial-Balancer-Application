package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.CityListing;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.costofliving.port.CityDirectory;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Postgres adapter for the signup dropdowns.
 *
 * <p>A country only appears once it has figures behind it, and a city reports the weakest
 * confidence and the oldest date among its own. Advertising the best of them would be the
 * flattering choice and the dishonest one: a plan is only as sound as the worst number in it.
 */
@Repository
class JpaCityDirectory implements CityDirectory {

    private final CountryRepository countries;
    private final MetroAreaRepository metros;
    private final CityCategoryBaselineRepository baselines;
    private final NationalBaselineRepository nationalBaselines;

    JpaCityDirectory(
            CountryRepository countries,
            MetroAreaRepository metros,
            CityCategoryBaselineRepository baselines,
            NationalBaselineRepository nationalBaselines) {
        this.countries = countries;
        this.metros = metros;
        this.baselines = baselines;
        this.nationalBaselines = nationalBaselines;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CountryProfile> listedCountries() {
        return countries.findByListedTrueOrderByName().stream()
                .filter(this::hasFigures)
                .map(CountryMapper::toProfile)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CountryProfile> country(CountryCode code) {
        return countries.findById(code.value()).map(CountryMapper::toProfile);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CityListing> citiesIn(CountryCode code) {
        return metros.findByCountryCodeOrderBySortRank(code.value()).stream()
                .map(this::toListing)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CityListing> city(MetroId metro) {
        return metros.findBySlug(metro.slug()).flatMap(this::toListing);
    }

    /** A city or a country-level estimate both count; either way the dropdown leads somewhere. */
    private boolean hasFigures(CountryEntity country) {
        return !metros.findByCountryCodeOrderBySortRank(country.code()).isEmpty()
                || !nationalBaselines.findByCountryCode(country.code()).isEmpty();
    }

    private Optional<CityListing> toListing(MetroAreaEntity area) {
        List<CityCategoryBaselineEntity> figures = baselines.findByMetroId(area.id());
        if (figures.isEmpty()) {
            return Optional.empty();
        }
        Confidence weakest = figures.stream()
                .map(CityCategoryBaselineEntity::confidence)
                .max(Comparator.comparingInt(Confidence::ordinal))
                .orElseThrow();
        LocalDate oldest = figures.stream()
                .map(CityCategoryBaselineEntity::asOf)
                .min(LocalDate::compareTo)
                .orElseThrow();
        return Optional.of(new CityListing(
                new MetroId(area.slug()),
                new CountryCode(area.countryCode()),
                area.displayName(),
                area.admin1(),
                weakest,
                oldest));
    }
}
