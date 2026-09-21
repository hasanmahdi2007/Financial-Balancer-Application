package com.hasan.budget.costofliving.classpath;

import com.hasan.budget.costofliving.classpath.CuratedDataset.CityFigure;
import com.hasan.budget.costofliving.classpath.CuratedDataset.MetroRow;
import com.hasan.budget.costofliving.domain.CityListing;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.costofliving.port.CityDirectory;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The signup dropdowns, served from the committed CSVs.
 *
 * <p>A country is only offered when it has figures behind it. A dropdown entry that leads nowhere is
 * worse than a short list: the user picks their own country, gets nothing, and has no way to tell
 * whether they did something wrong.
 *
 * <p>Each city reports the <em>weakest</em> confidence and the <em>oldest</em> date among its own
 * figures. Advertising the best of them would be the flattering choice and the dishonest one, since
 * a plan is only as sound as the worst number in it.
 */
public final class ClasspathCityDirectory implements CityDirectory {

    private final CuratedDataset dataset;

    public ClasspathCityDirectory(CuratedDataset dataset) {
        this.dataset = Objects.requireNonNull(dataset, "dataset");
    }

    @Override
    public List<CountryProfile> listedCountries() {
        return dataset.countries().stream()
                .filter(CountryProfile::listed)
                .filter(this::hasFigures)
                .sorted(Comparator.comparing(CountryProfile::name))
                .toList();
    }

    @Override
    public Optional<CountryProfile> country(CountryCode code) {
        return dataset.country(code);
    }

    @Override
    public List<CityListing> citiesIn(CountryCode code) {
        return dataset.metros().stream()
                .filter(m -> m.country().equals(code))
                .sorted(Comparator.comparingInt(MetroRow::sortRank))
                .map(this::toListing)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Optional<CityListing> city(MetroId metro) {
        return Optional.ofNullable(dataset.metrosById().get(metro)).flatMap(this::toListing);
    }

    /** A country counts as having data when it has a city or a country-level estimate. */
    private boolean hasFigures(CountryProfile country) {
        boolean hasCity = dataset.metros().stream().anyMatch(m -> m.country().equals(country.code()));
        boolean hasEstimate =
                dataset.countryFigures().stream().anyMatch(f -> f.country().equals(country.code()));
        return hasCity || hasEstimate;
    }

    private Optional<CityListing> toListing(MetroRow row) {
        List<CityFigure> figures = dataset.cityFigures().stream()
                .filter(f -> f.metro().equals(row.metro()))
                .toList();
        if (figures.isEmpty()) {
            return Optional.empty();
        }
        Confidence weakest = figures.stream()
                .map(CityFigure::confidence)
                .max(Comparator.comparingInt(Confidence::ordinal))
                .orElseThrow();
        LocalDate oldest = figures.stream().map(CityFigure::asOf).min(LocalDate::compareTo).orElseThrow();
        return Optional.of(new CityListing(
                row.metro(), row.country(), row.displayName(), row.admin1(), weakest, oldest));
    }
}
