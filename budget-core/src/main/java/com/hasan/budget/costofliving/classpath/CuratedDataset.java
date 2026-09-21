package com.hasan.budget.costofliving.classpath;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.costofliving.domain.PlausibleBand;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The committed CSVs, parsed once.
 *
 * <p>This is the single reading of the seed files, and both paths into the product go through it:
 * the classpath adapter serves it directly, and the Flyway migration inserts it into Postgres. That
 * is what makes "both providers read the same committed data" a fact rather than an intention - the
 * database cannot drift from the files, because the files are what filled it.
 *
 * <p>Loading is eager and total. A malformed row fails here, at startup or at migration time, rather
 * than at the moment some user in Zahle asks for a grocery baseline.
 */
public final class CuratedDataset {

    private static final String COUNTRIES = "data/countries.csv";
    private static final String SOURCES = "data/data_sources.csv";
    private static final String METROS = "data/metro_areas.csv";
    private static final String CITY_BASELINES = "data/city_category_baseline.csv";
    private static final String NATIONAL_BASELINES = "data/national_baseline.csv";

    /** A city we hold data for, in file order. */
    public record MetroRow(
            MetroId metro,
            CountryCode country,
            String displayName,
            String admin1,
            Integer population,
            int sortRank) {}

    /** Where a figure came from, said plainly enough that nobody mistakes it for a statistic. */
    public record SourceRow(
            String id, String name, String url, LocalDate retrievedAt, String licenseNote) {}

    /**
     * One city figure.
     *
     * @param quintile null on every curated row, and that is the point: curated figures are not
     *     split by income while a future derived source will be, which is why the column is nullable.
     */
    public record CityFigure(
            MetroId metro,
            SpendCategory category,
            IncomeQuintile quintile,
            Money amount,
            Confidence confidence,
            String sourceId,
            LocalDate asOf) {}

    /** One country-level estimate, carrying the band that says what a believable claim looks like. */
    public record CountryFigure(
            CountryCode country,
            SpendCategory category,
            IncomeQuintile quintile,
            Money amount,
            Confidence confidence,
            String sourceId,
            LocalDate asOf,
            PlausibleBand band) {}

    private final List<CountryProfile> countries;
    private final List<SourceRow> sources;
    private final List<MetroRow> metros;
    private final List<CityFigure> cityFigures;
    private final List<CountryFigure> countryFigures;

    private CuratedDataset(
            List<CountryProfile> countries,
            List<SourceRow> sources,
            List<MetroRow> metros,
            List<CityFigure> cityFigures,
            List<CountryFigure> countryFigures) {
        this.countries = countries;
        this.sources = sources;
        this.metros = metros;
        this.cityFigures = cityFigures;
        this.countryFigures = countryFigures;
    }

    public static CuratedDataset load() {
        return new CuratedDataset(
                readCountries(), readSources(), readMetros(), readCityFigures(), readCountryFigures());
    }

    public List<CountryProfile> countries() {
        return countries;
    }

    public List<SourceRow> sources() {
        return sources;
    }

    public List<MetroRow> metros() {
        return metros;
    }

    public List<CityFigure> cityFigures() {
        return cityFigures;
    }

    public List<CountryFigure> countryFigures() {
        return countryFigures;
    }

    /** Human-readable provenance for a source id, which is what a resolved baseline carries. */
    public String sourceName(String sourceId) {
        return sources.stream()
                .filter(s -> s.id().equals(sourceId))
                .map(SourceRow::name)
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("seed data references unknown source " + sourceId));
    }

    public Optional<CountryProfile> country(CountryCode code) {
        return countries.stream().filter(c -> c.code().equals(code)).findFirst();
    }

    public Map<MetroId, MetroRow> metrosById() {
        return metros.stream().collect(Collectors.toMap(MetroRow::metro, Function.identity()));
    }

    private static List<CountryProfile> readCountries() {
        return CsvTable.read(COUNTRIES).rows().stream()
                .map(row -> CountryProfile.fromPercent(
                        new CountryCode(CsvTable.required(row, "code")),
                        CsvTable.required(row, "name"),
                        CsvTable.required(row, "currency"),
                        new BigDecimal(CsvTable.required(row, "annual_inflation_pct")),
                        LocalDate.parse(CsvTable.required(row, "inflation_as_of")),
                        Boolean.parseBoolean(CsvTable.required(row, "listed")),
                        CsvTable.optional(row, "data_note").orElse("")))
                .toList();
    }

    private static List<SourceRow> readSources() {
        return CsvTable.read(SOURCES).rows().stream()
                .map(row -> new SourceRow(
                        CsvTable.required(row, "id"),
                        CsvTable.required(row, "name"),
                        CsvTable.optional(row, "url").orElse(null),
                        LocalDate.parse(CsvTable.required(row, "retrieved_at")),
                        CsvTable.required(row, "license_note")))
                .toList();
    }

    private static List<MetroRow> readMetros() {
        return CsvTable.read(METROS).rows().stream()
                .map(row -> new MetroRow(
                        new MetroId(CsvTable.required(row, "slug")),
                        new CountryCode(CsvTable.required(row, "country_code")),
                        CsvTable.required(row, "display_name"),
                        CsvTable.optional(row, "admin1").orElse(null),
                        CsvTable.optional(row, "population").map(Integer::valueOf).orElse(null),
                        Integer.parseInt(CsvTable.required(row, "sort_rank"))))
                .toList();
    }

    private static List<CityFigure> readCityFigures() {
        return CsvTable.read(CITY_BASELINES).rows().stream()
                .map(row -> new CityFigure(
                        new MetroId(CsvTable.required(row, "metro_slug")),
                        SpendCategory.valueOf(CsvTable.required(row, "category")),
                        quintile(row),
                        Money.of(CsvTable.required(row, "monthly_amount")),
                        Confidence.valueOf(CsvTable.required(row, "confidence")),
                        CsvTable.required(row, "source_id"),
                        LocalDate.parse(CsvTable.required(row, "as_of"))))
                .toList();
    }

    private static List<CountryFigure> readCountryFigures() {
        return CsvTable.read(NATIONAL_BASELINES).rows().stream()
                .map(row -> new CountryFigure(
                        new CountryCode(CsvTable.required(row, "country_code")),
                        SpendCategory.valueOf(CsvTable.required(row, "category")),
                        quintile(row),
                        Money.of(CsvTable.required(row, "monthly_amount")),
                        Confidence.valueOf(CsvTable.required(row, "confidence")),
                        CsvTable.required(row, "source_id"),
                        LocalDate.parse(CsvTable.required(row, "as_of")),
                        new PlausibleBand(
                                Integer.parseInt(CsvTable.required(row, "low_pct")),
                                Integer.parseInt(CsvTable.required(row, "high_pct")))))
                .toList();
    }

    private static IncomeQuintile quintile(Map<String, String> row) {
        return CsvTable.optional(row, "income_quintile").map(IncomeQuintile::valueOf).orElse(null);
    }
}
