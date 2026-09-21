package com.hasan.budget.costofliving.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.costofliving.classpath.CuratedDataset.CityFigure;
import com.hasan.budget.costofliving.classpath.CuratedDataset.CountryFigure;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the committed seed files themselves.
 *
 * <p>These figures are typed by hand, and a mis-keyed one is the quietest failure this project has:
 * nothing crashes, the plan balances, and the advice is wrong. Checking the data is as worthwhile as
 * checking the code that reads it, and costs a fraction as much.
 */
class CuratedDataTest {

    private final CuratedDataset dataset = CuratedDataset.load();

    @Test
    @DisplayName("no curated figure is labelled as official statistics")
    void curatedFiguresAreNeverLabelledOfficial() {
        // The one dishonesty that would discredit the whole confidence model. These numbers were
        // researched from listings and local prices; OFFICIAL is reserved for figures derived from
        // published government releases, which is deferred and not built.
        assertThat(dataset.cityFigures())
                .isNotEmpty()
                .allSatisfy(figure ->
                        assertThat(figure.confidence()).isEqualTo(Confidence.CROWDSOURCED));
        assertThat(dataset.countryFigures())
                .isNotEmpty()
                .allSatisfy(figure ->
                        assertThat(figure.confidence()).isEqualTo(Confidence.ESTIMATED));
    }

    @Test
    @DisplayName("every curated figure leaves the income quintile empty")
    void curatedFiguresCarryNoIncomeQuintile() {
        // Not an oversight. Curated figures are not split by income, and the column stays nullable
        // so that a future source which does split by income needs a new class rather than a
        // migration and a backfill.
        assertThat(dataset.cityFigures()).allSatisfy(f -> assertThat(f.quintile()).isNull());
        assertThat(dataset.countryFigures()).allSatisfy(f -> assertThat(f.quintile()).isNull());
    }

    @Test
    @DisplayName("every city figure sits inside its own country's plausible band")
    void everyCityFigureIsBelievableAgainstItsCountryEstimate() {
        // The band is what rejects a user typing rent into the groceries box. If the curated data
        // itself fell outside it, the band would be wrong rather than the submission - and a real
        // resident of Wichita or San Francisco would be told their own rent is implausible.
        for (CityFigure figure : dataset.cityFigures()) {
            CountryCode country = countryOf(figure.metro());
            CountryFigure estimate = estimateFor(country, figure);
            assertThat(estimate.band().accepts(figure.amount(), estimate.amount()))
                    .describedAs(
                            "%s in %s is %s, outside %s..%s",
                            figure.category(),
                            figure.metro().slug(),
                            figure.amount(),
                            estimate.band().lowerBound(estimate.amount()),
                            estimate.band().upperBound(estimate.amount()))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("every listed country actually has figures behind it")
    void aListedCountryIsNeverADeadEnd() {
        for (CountryProfile country : dataset.countries()) {
            if (!country.listed()) {
                continue;
            }
            assertThat(dataset.metros())
                    .describedAs("cities in %s", country.name())
                    .anyMatch(metro -> metro.country().equals(country.code()));
            assertThat(dataset.countryFigures())
                    .describedAs("country estimates for %s", country.name())
                    .anyMatch(figure -> figure.country().equals(country.code()));
        }
    }

    @Test
    @DisplayName("every figure names a source that exists and says what it is")
    void everyFigureHasRealProvenance() {
        dataset.cityFigures().forEach(f -> assertThat(dataset.sourceName(f.sourceId())).isNotBlank());
        dataset.countryFigures().forEach(f -> assertThat(dataset.sourceName(f.sourceId())).isNotBlank());
        assertThat(dataset.sources()).allSatisfy(source -> {
            assertThat(source.licenseNote())
                    .describedAs("what %s actually is", source.id())
                    .isNotBlank();
            assertThat(source.retrievedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("every city has a figure for each category the plan needs")
    void everyCityCoversTheSameCategories() {
        // A city missing a category falls back to the country estimate, which works but quietly
        // makes that city's plan weaker than it looks. Better to notice a gap here.
        long categoriesPerCity = dataset.cityFigures().stream()
                .filter(f -> f.metro().equals(new MetroId("beirut")))
                .count();
        dataset.metros().forEach(metro -> assertThat(dataset.cityFigures().stream()
                        .filter(f -> f.metro().equals(metro.metro()))
                        .count())
                .describedAs("categories covered for %s", metro.metro().slug())
                .isEqualTo(categoriesPerCity));
    }

    private CountryCode countryOf(MetroId metro) {
        return dataset.metrosById().get(metro).country();
    }

    private CountryFigure estimateFor(CountryCode country, CityFigure figure) {
        return dataset.countryFigures().stream()
                .filter(f -> f.country().equals(country) && f.category() == figure.category())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no country estimate for " + figure.category() + " in " + country.value()));
    }
}
