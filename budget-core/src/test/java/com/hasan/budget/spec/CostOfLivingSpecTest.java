package com.hasan.budget.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.hasan.budget.costofliving.application.BaselineResolver;
import com.hasan.budget.costofliving.application.CityCatalogService;
import com.hasan.budget.costofliving.application.CityDataLayer;
import com.hasan.budget.costofliving.application.ContributionService;
import com.hasan.budget.costofliving.application.ContributionValidator;
import com.hasan.budget.costofliving.application.CountryEstimateLayer;
import com.hasan.budget.costofliving.application.NoOfficialData;
import com.hasan.budget.costofliving.application.UserFigureService;
import com.hasan.budget.costofliving.application.UserOverrideLayer;
import com.hasan.budget.costofliving.classpath.ClasspathCityDirectory;
import com.hasan.budget.costofliving.classpath.ClasspathCostOfLivingProvider;
import com.hasan.budget.costofliving.classpath.ClasspathCountryBaselineSource;
import com.hasan.budget.costofliving.classpath.CuratedDataset;
import com.hasan.budget.costofliving.contract.CostOfLivingProviderContract;
import com.hasan.budget.costofliving.domain.BankEvidence;
import com.hasan.budget.costofliving.domain.BaselineQuery;
import com.hasan.budget.costofliving.domain.CityListing;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.Contribution;
import com.hasan.budget.costofliving.domain.ContributionState;
import com.hasan.budget.costofliving.domain.Corroboration;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.Staleness;
import com.hasan.budget.costofliving.domain.StalenessPolicy;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import com.hasan.budget.costofliving.support.InMemoryContributionStore;
import com.hasan.budget.costofliving.support.InMemoryCostOfLivingProvider;
import com.hasan.budget.costofliving.support.InMemoryUserLocationStore;
import com.hasan.budget.costofliving.support.InMemoryUserOverrideStore;
import com.hasan.budget.profile.domain.SpendingQuestion;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * Executable specification for cost-of-living resolution (Stages 2 and 3).
 *
 * <p>This is the area where an error is silent: a wrong baseline produces a plausible-looking plan
 * with wrong numbers throughout, and nothing crashes. Hence the emphasis on magnitude checks
 * against an independent anchor rather than on "the code returns something".
 *
 * <p>Every behaviour below runs with no database and no network. The committed-CSV adapter is the
 * source under test, and the Postgres adapter is held to the same specification separately through
 * {@link CostOfLivingProviderContract} - see the last behaviour in this file.
 */
class CostOfLivingSpecTest {

    /** The day the Lebanese figures were gathered, so nothing has aged unless a test ages it. */
    private static final Clock TODAY = fixedAt("2026-09-20");

    private static final String AUTHOR = "user-who-typed-it";
    private static final String SOMEBODY_ELSE = "user-who-did-not";
    private static final MetroId BEIRUT = new MetroId("beirut");
    private static final MetroId WICHITA = new MetroId("wichita");
    private static final CountryCode LEBANON = CountryCode.LEBANON;

    private final CuratedDataset curated = CuratedDataset.load();
    private final InMemoryUserOverrideStore overrides = new InMemoryUserOverrideStore();
    private final InMemoryUserLocationStore locations = new InMemoryUserLocationStore();

    private static Clock fixedAt(String day) {
        return Clock.fixed(Instant.parse(day + "T00:00:00Z"), ZoneOffset.UTC);
    }

    private ClasspathCityDirectory directory() {
        return new ClasspathCityDirectory(curated);
    }

    private ClasspathCountryBaselineSource countryEstimates(Clock clock) {
        return new ClasspathCountryBaselineSource(curated, clock);
    }

    private ClasspathCostOfLivingProvider curatedCities(Clock clock) {
        return new ClasspathCostOfLivingProvider(curated, clock);
    }

    /**
     * The production wiring, with the city source swappable so a test can place figures at exact
     * tiers. The order is the precedence rule in full, and it is the same list the Spring
     * configuration builds.
     */
    private BaselineResolver resolver(
            Clock clock, CostOfLivingProvider official, CostOfLivingProvider cityData) {
        return new BaselineResolver(List.of(
                new UserOverrideLayer(overrides),
                new CityDataLayer(Confidence.OFFICIAL, official),
                new CityDataLayer(Confidence.CONTRIBUTED, cityData),
                new CityDataLayer(Confidence.CROWDSOURCED, cityData),
                new CountryEstimateLayer(countryEstimates(clock))));
    }

    private BaselineResolver resolverOverCuratedData(Clock clock) {
        return resolver(clock, new NoOfficialData(), curatedCities(clock));
    }

    private BaselineQuery ask(String userId, MetroId city, SpendCategory category) {
        return new BaselineQuery(userId, LEBANON, city, category, IncomeQuintile.Q3);
    }

    @Nested
    @DisplayName("the localised baseline formula")
    @Disabled("Deferred out of v1 with the BLS/BEA/Census derivation these four behaviours test. "
            + "v1 ships curated figures, so there is no formula to exercise yet. The full "
            + "specification, the reference figures and the experiment that must run before any of "
            + "it is trusted are in .claude/packets/P9-DEFERRED-official-cost-of-living.md. The "
            + "OFFICIAL confidence tier and an empty OFFICIAL resolver layer both exist and are "
            + "covered by the behaviours below, so reviving this is a new class rather than a "
            + "refactor.")
    class Formula {

        /**
         * localBaseline = BLS_CEX(category, quintile) × BEA_RPP_component(metro) / 100.
         *
         * <p>Direction alone is a useless assertion — almost any bug still makes San Francisco
         * dearer than Wichita. The ratio of the two housing baselines must track the ratio of their
         * RPP rents components to within a few percent.
         */
        @Test
        void housingBaselinesScaleWithTheRentsParityInMagnitudeNotJustDirection() {
            fail("not implemented");
        }

        /** Food and goods use the GOODS component; services use OTHER_SERVICES. */
        @Test
        void eachCategoryIsLocalisedByItsOwnPriceComponent() {
            fail("not implemented");
        }

        /**
         * The highest-value check in the project, and the cheapest. For five metros, the derived
         * housing baseline is compared against Census ACS B25064 median gross rent — an entirely
         * independent source. Within ~15% is defensible; a 2x gap means the category-to-component
         * mapping is wrong and everything downstream is poisoned.
         */
        @Test
        void derivedHousingBaselinesAgreeWithCensusMedianRentWithinFifteenPercent() {
            fail("not implemented");
        }

        /**
         * Quintiles matter: someone on $40k and someone on $150k have nothing in common on housing,
         * so a single national average would be useless. A higher quintile must give a higher
         * baseline for the same metro and category.
         */
        @Test
        void higherIncomeQuintilesProduceHigherBaselines() {
            fail("not implemented");
        }
    }

    @Nested
    @DisplayName("resolution precedence")
    class Precedence {

        /**
         * A user's own figure beats everything, including OFFICIAL government data, and is tagged
         * USER_PROVIDED so the UI says "your figure" rather than "government statistic". If the app
         * estimates $400 for groceries and the user knows it is $250, $250 wins, permanently.
         */
        @Test
        void aUserOverrideBeatsEvenOfficialData() {
            InMemoryCostOfLivingProvider official = new InMemoryCostOfLivingProvider()
                    .with(
                            BEIRUT,
                            SpendCategory.GROCERIES,
                            Money.of("400.00"),
                            Confidence.OFFICIAL,
                            "a government statistical release",
                            LocalDate.parse("2026-01-01"));
            UserFigureService userFigures =
                    new UserFigureService(overrides, directory(), TODAY);
            userFigures.record(
                    AUTHOR, SpendCategory.GROCERIES, Money.of("250.00"), Optional.empty());

            ResolvedBaseline resolved = resolver(TODAY, official, curatedCities(TODAY))
                    .resolve(ask(AUTHOR, BEIRUT, SpendCategory.GROCERIES))
                    .orElseThrow();

            assertThat(resolved.amount()).isEqualTo(Money.of("250.00"));
            assertThat(resolved.confidence()).isEqualTo(Confidence.USER_PROVIDED);
            // The label a person reads must say whose number this is, not what tier it came from.
            assertThat(resolved.confidence().label()).isEqualTo("Your own figure");
        }

        /** OFFICIAL, then CONTRIBUTED, then CROWDSOURCED, then the country-level ESTIMATED fallback. */
        @Test
        void sourcesAreConsultedInConfidenceOrder() {
            InMemoryCostOfLivingProvider official = new InMemoryCostOfLivingProvider();
            InMemoryCostOfLivingProvider cityData = new InMemoryCostOfLivingProvider();
            BaselineResolver resolver = resolver(TODAY, official, cityData);
            BaselineQuery query = ask(SOMEBODY_ELSE, BEIRUT, SpendCategory.GROCERIES);

            // Nothing about the city at all: the country estimate answers.
            assertThat(resolver.resolve(query).orElseThrow().confidence())
                    .isEqualTo(Confidence.ESTIMATED);

            cityData.with(
                    BEIRUT,
                    SpendCategory.GROCERIES,
                    Money.of("300.00"),
                    Confidence.CROWDSOURCED,
                    "our own research",
                    LocalDate.parse("2026-09-01"));
            assertThat(resolver.resolve(query).orElseThrow().amount()).isEqualTo(Money.of("300.00"));

            cityData.with(
                    BEIRUT,
                    SpendCategory.GROCERIES,
                    Money.of("280.00"),
                    Confidence.CONTRIBUTED,
                    "someone who lives there",
                    LocalDate.parse("2026-09-10"));
            assertThat(resolver.resolve(query).orElseThrow().amount()).isEqualTo(Money.of("280.00"));

            official.with(
                    BEIRUT,
                    SpendCategory.GROCERIES,
                    Money.of("310.00"),
                    Confidence.OFFICIAL,
                    "a government statistical release",
                    LocalDate.parse("2026-06-01"));
            assertThat(resolver.resolve(query).orElseThrow().amount()).isEqualTo(Money.of("310.00"));

            overrides.save(new com.hasan.budget.costofliving.domain.UserOverride(
                    SOMEBODY_ELSE,
                    SpendCategory.GROCERIES,
                    Money.of("250.00"),
                    LocalDate.parse("2026-09-20"),
                    Corroboration.NOT_CHECKED));
            assertThat(resolver.resolve(query).orElseThrow().amount()).isEqualTo(Money.of("250.00"));
        }

        /** A city with no row for a category falls back to the country estimate, never to zero. */
        @Test
        void aMissingCategoryFallsBackRatherThanReturningNothing() {
            InMemoryCostOfLivingProvider cityData = new InMemoryCostOfLivingProvider()
                    .with(
                            BEIRUT,
                            SpendCategory.RENT,
                            Money.of("600.00"),
                            Confidence.CROWDSOURCED,
                            "our own research",
                            LocalDate.parse("2026-09-01"));

            ResolvedBaseline groceries = resolver(TODAY, new NoOfficialData(), cityData)
                    .resolve(ask(SOMEBODY_ELSE, BEIRUT, SpendCategory.GROCERIES))
                    .orElseThrow();

            // Zero would be read downstream as "groceries cost nothing here", which is not a
            // smaller error than a rough estimate - it is a silent one.
            assertThat(groceries.amount()).isEqualTo(Money.of("250.00"));
            assertThat(groceries.amount().isPositive()).isTrue();
            assertThat(groceries.confidence()).isEqualTo(Confidence.ESTIMATED);
            assertThat(groceries.confidence().meaning()).contains("country");
        }

        /**
         * A bare Money loses provenance, and then the UI cannot honestly label anything. Every
         * resolution carries its confidence, source and as-of date.
         */
        @Test
        void everyResolvedBaselineCarriesItsConfidenceAndSource() {
            Map<SpendCategory, ResolvedBaseline> everything =
                    resolverOverCuratedData(TODAY).resolveAll(ask(SOMEBODY_ELSE, BEIRUT, SpendCategory.RENT));

            assertThat(everything).isNotEmpty();
            assertThat(everything.values()).allSatisfy(baseline -> {
                assertThat(baseline.confidence()).isNotNull();
                assertThat(baseline.sourceName()).isNotBlank();
                assertThat(baseline.asOf()).isNotNull();
                assertThat(baseline.staleness()).isNotNull();
                // And the provenance has to be sayable to a person, not just present.
                assertThat(baseline.confidence().label()).isNotBlank();
                assertThat(baseline.confidence().meaning()).isNotBlank();
            });
        }

        /**
         * Nothing populates the OFFICIAL layer in v1. The chain must fall straight through it to the
         * curated data rather than stopping, returning empty, or labelling a curated figure as
         * official - which is what would happen if the empty slot were skipped instead of consulted.
         */
        @Test
        @DisplayName("the empty official layer falls through cleanly to the curated figures")
        void anEmptyOfficialLayerIsSkippedRatherThanFailing() {
            BaselineResolver resolver = resolverOverCuratedData(TODAY);

            assertThat(resolver.layers())
                    .extracting(layer -> layer.tier())
                    .containsExactly(
                            Confidence.USER_PROVIDED,
                            Confidence.OFFICIAL,
                            Confidence.CONTRIBUTED,
                            Confidence.CROWDSOURCED,
                            Confidence.ESTIMATED);

            ResolvedBaseline rent = resolver
                    .resolve(ask(SOMEBODY_ELSE, BEIRUT, SpendCategory.RENT))
                    .orElseThrow();

            assertThat(rent.confidence()).isEqualTo(Confidence.CROWDSOURCED);
            assertThat(rent.amount()).isEqualTo(Money.of("600.00"));
        }

        /**
         * Every curated row leaves the income quintile empty, because these figures are not split by
         * income. Resolution has to work through that today, and keep working if a later source
         * starts filling the column in for some cities and not others.
         */
        @Test
        @DisplayName("a figure with no income quintile resolves for every quintile")
        void aNullIncomeQuintileDoesNotBreakResolution() {
            for (IncomeQuintile quintile : IncomeQuintile.values()) {
                ResolvedBaseline rent = resolverOverCuratedData(TODAY)
                        .resolve(new BaselineQuery(
                                SOMEBODY_ELSE, LEBANON, BEIRUT, SpendCategory.RENT, quintile))
                        .orElseThrow();

                assertThat(rent.amount())
                        .describedAs("Beirut rent for %s", quintile)
                        .isEqualTo(Money.of("600.00"));
            }
        }
    }

    @Nested
    @DisplayName("staleness is derived from inflation, not hardcoded")
    class StalenessRules {

        /** drift = annual_inflation × age_months / 12; FRESH under 5%, AGING to 15%, STALE beyond. */
        @Test
        void driftIsComputedFromTheCountrysInflationRate() {
            Clock sixMonthsOn = fixedAt("2027-03-20");
            CountryProfile lebanon = directory().country(LEBANON).orElseThrow();

            ResolvedBaseline rent = resolverOverCuratedData(sixMonthsOn)
                    .resolve(ask(SOMEBODY_ELSE, BEIRUT, SpendCategory.RENT))
                    .orElseThrow();

            // Six months of 17.3% a year is 8.65% of drift, which sits in the middle band.
            assertThat(StalenessPolicy.driftPercent(
                            rent.asOf(),
                            LocalDate.now(sixMonthsOn),
                            lebanon.annualInflationBasisPoints()))
                    .isEqualByComparingTo("8.65");
            assertThat(rent.staleness()).isEqualTo(Staleness.AGING);
        }

        /** The US at ~3%/yr keeps a figure FRESH for roughly twenty months. */
        @Test
        void lowInflationCountriesStayFreshForYears() {
            Clock seventeenMonthsOn = fixedAt("2027-06-20");

            ResolvedBaseline wichita = curatedCities(seventeenMonthsOn)
                    .baselineFor(WICHITA, SpendCategory.RENT, IncomeQuintile.Q3)
                    .orElseThrow();

            assertThat(wichita.asOf()).isEqualTo(LocalDate.parse("2026-01-01"));
            assertThat(wichita.staleness()).isEqualTo(Staleness.FRESH);
        }

        /**
         * Same code, opposite behaviour, driven entirely by one column: Lebanon at 17.3%/yr turns
         * AGING in about three and a half months and STALE in about ten.
         */
        @Test
        void highInflationCountriesGoStaleWithinMonths() {
            ResolvedBaseline agingAtFourMonths = curatedCities(fixedAt("2027-01-20"))
                    .baselineFor(BEIRUT, SpendCategory.RENT, IncomeQuintile.Q3)
                    .orElseThrow();
            ResolvedBaseline staleAtElevenMonths = curatedCities(fixedAt("2027-08-20"))
                    .baselineFor(BEIRUT, SpendCategory.RENT, IncomeQuintile.Q3)
                    .orElseThrow();

            assertThat(agingAtFourMonths.staleness()).isEqualTo(Staleness.AGING);
            assertThat(staleAtElevenMonths.staleness()).isEqualTo(Staleness.STALE);

            // The contrast is the point. At the very same date the older Wichita figure is still
            // fresh, because what ages a number is inflation rather than the calendar.
            assertThat(curatedCities(fixedAt("2027-08-20"))
                            .baselineFor(WICHITA, SpendCategory.RENT, IncomeQuintile.Q3)
                            .orElseThrow()
                            .staleness())
                    .isEqualTo(Staleness.FRESH);
        }

        /**
         * Inflating a stored figure fabricates precision it never had. The adjusted number may be
         * offered as a pre-filled default for the user to confirm, at which point it becomes
         * USER_PROVIDED — but it is never silently written back wearing a CROWDSOURCED badge.
         */
        @Test
        void staleFiguresAreFlaggedButNeverSilentlyInflated() {
            Clock elevenMonthsOn = fixedAt("2027-08-20");
            BaselineResolver resolver = resolverOverCuratedData(elevenMonthsOn);
            UserFigureService userFigures =
                    new UserFigureService(overrides, directory(), elevenMonthsOn);
            BaselineQuery query = ask(AUTHOR, BEIRUT, SpendCategory.RENT);

            ResolvedBaseline stale = resolver.resolve(query).orElseThrow();
            assertThat(stale.staleness()).isEqualTo(Staleness.STALE);
            assertThat(stale.amount()).isEqualTo(Money.of("600.00"));
            assertThat(stale.confidence()).isEqualTo(Confidence.CROWDSOURCED);

            SpendingQuestion confirmation = userFigures
                    .confirmationFor(LEBANON, SpendCategory.RENT, stale)
                    .orElseThrow();

            // Eleven months of 17.3% a year on 600 is 695.10, and it is a suggestion to be
            // confirmed rather than a number quietly substituted for the stored one.
            assertThat(confirmation.suggested()).isEqualTo(Money.of("695.10"));
            assertThat(confirmation.basis()).contains("suggestion");
            assertThat(resolver.resolve(query).orElseThrow().amount()).isEqualTo(Money.of("600.00"));

            userFigures.record(AUTHOR, SpendCategory.RENT, confirmation.suggested(), Optional.empty());

            ResolvedBaseline confirmed = resolver.resolve(query).orElseThrow();
            assertThat(confirmed.amount()).isEqualTo(Money.of("695.10"));
            assertThat(confirmed.confidence()).isEqualTo(Confidence.USER_PROVIDED);
        }
    }

    @Nested
    @DisplayName("the city catalogue and the manual escape hatch")
    class Catalogue {

        private CityCatalogService catalogue() {
            return new CityCatalogService(directory(), countryEstimates(TODAY), locations);
        }

        /** Only countries we actually hold data for are listed; the dropdown cannot offer a dead end. */
        @Test
        void theCountryListContainsOnlyCountriesWithData() {
            List<CountryProfile> countries = catalogue().countries();

            assertThat(countries).isNotEmpty();
            assertThat(countries).allSatisfy(country -> {
                assertThat(country.listed()).isTrue();
                assertThat(catalogue().cities(country.code()))
                        .describedAs("cities offered for %s", country.name())
                        .isNotEmpty();
            });
            assertThat(countries).extracting(c -> c.code().value()).contains("LB", "US");
        }

        /** Selecting a country returns only that country's cities, each with confidence and as-of. */
        @Test
        void selectingACountryReturnsItsCitiesWithProvenance() {
            List<CityListing> lebanese = catalogue().cities(LEBANON);

            assertThat(lebanese).extracting(c -> c.metro().slug()).contains("beirut", "tripoli-lb");
            assertThat(lebanese).allSatisfy(city -> {
                assertThat(city.country()).isEqualTo(LEBANON);
                assertThat(city.confidence()).isEqualTo(Confidence.CROWDSOURCED);
                assertThat(city.asOf()).isNotNull();
                assertThat(city.displayName()).isNotBlank();
            });
            assertThat(catalogue().cities(CountryCode.US))
                    .extracting(c -> c.metro().slug())
                    .doesNotContain("beirut");
        }

        /**
         * Eleven blank boxes is where users abandon signup. The manual form arrives pre-filled from
         * the country-level ESTIMATED layer, so the user edits what they know instead of typing
         * everything from scratch.
         */
        @Test
        void theManualFormIsPrefilledFromTheCountryEstimate() {
            List<SpendingQuestion> form = catalogue().manualForm(LEBANON);

            assertThat(form).hasSize(10);
            assertThat(form).allSatisfy(question -> {
                assertThat(question.suggested().isPositive())
                        .describedAs("%s arrives pre-filled", question.question())
                        .isTrue();
                assertThat(question.basis()).isNotBlank();
                assertThat(question.explainedCoverage()).isNotEmpty();
                // Nothing a person reads may be a constant name or an internal word.
                assertThat(question.question() + question.why() + question.basis())
                        .doesNotContain("ESTIMATED", "CROWDSOURCED", "baseline", "DINING_OUT");
            });

            SpendingQuestion rent = form.stream()
                    .filter(q -> q.covers().contains(SpendCategory.RENT))
                    .findFirst()
                    .orElseThrow();
            assertThat(rent.suggested()).isEqualTo(Money.of("350.00"));
            assertThat(rent.explainedCoverage()).containsExactly("Rent - your rent or mortgage payment");
        }

        /**
         * A typed city name is display-only and never a lookup key. This is what removes geocoding
         * and fuzzy matching entirely: a typo can never almost-match the wrong metro.
         */
        @Test
        void aTypedCityNameNeverResolvesToASeededMetro() {
            // The slug the user picked from the dropdown resolves. Everything a person might
            // plausibly type for the same city does not, and that is the whole safety property:
            // there is no near-match, so there is no wrong near-match either.
            assertThat(catalogue().city(BEIRUT)).isPresent();
            for (String typed : List.of("Beirut", "beyrouth", "Beirut, Lebanon", "bierut", "BEIRUT")) {
                assertThat(catalogue().city(new MetroId(typed)))
                        .describedAs("a user typing %s", typed)
                        .isEmpty();
            }

            // Saving what they typed changes none of that. The label is kept to show back to them;
            // it never becomes a key, so the same typing that failed above still fails afterwards.
            catalogue().recordManualLocation(SOMEBODY_ELSE, LEBANON, "Bcharre");
            assertThat(catalogue().manualLocationOf(SOMEBODY_ELSE).orElseThrow().cityLabel())
                    .isEqualTo("Bcharre");
            assertThat(catalogue().city(new MetroId("Bcharre"))).isEmpty();

            // And the figures such a user gets are their country's, labelled as an estimate,
            // rather than some other city's labelled as that city's.
            ResolvedBaseline rent = resolverOverCuratedData(TODAY)
                    .resolve(new BaselineQuery(
                            SOMEBODY_ELSE, LEBANON, null, SpendCategory.RENT, IncomeQuintile.Q3))
                    .orElseThrow();
            assertThat(rent.confidence()).isEqualTo(Confidence.ESTIMATED);
            assertThat(rent.amount()).isEqualTo(Money.of("350.00"));
        }
    }

    @Nested
    @DisplayName("user-contributed data stays private until validated")
    class Contributions {

        private final InMemoryCostOfLivingProvider cityData = new InMemoryCostOfLivingProvider()
                .with(
                        BEIRUT,
                        SpendCategory.GROCERIES,
                        Money.of("300.00"),
                        Confidence.CROWDSOURCED,
                        "our own research",
                        LocalDate.parse("2026-09-01"))
                .with(
                        BEIRUT,
                        SpendCategory.RENT,
                        Money.of("600.00"),
                        Confidence.CROWDSOURCED,
                        "our own research",
                        LocalDate.parse("2026-09-01"));

        private final InMemoryContributionStore store = new InMemoryContributionStore(cityData);
        private final UserFigureService userFigures =
                new UserFigureService(overrides, directory(), TODAY);
        private final ContributionService contributions = new ContributionService(
                store,
                new ContributionValidator(countryEstimates(TODAY), userFigures),
                userFigures,
                TODAY);

        private Contribution submit(SpendCategory category, String claimed, String observed) {
            return contributions.submit(
                    AUTHOR,
                    LEBANON,
                    BEIRUT,
                    "Beirut",
                    category,
                    Money.of(claimed),
                    Optional.of(new BankEvidence(category, Money.of(observed))));
        }

        /** What the user types affects their own plan immediately, and nobody else's. */
        @Test
        void aContributionAffectsOnlyItsAuthorUntilItIsPublished() {
            BaselineResolver resolver = resolver(TODAY, new NoOfficialData(), cityData);

            Contribution submitted = submit(SpendCategory.GROCERIES, "270.00", "265.00");

            assertThat(resolver.resolve(ask(AUTHOR, BEIRUT, SpendCategory.GROCERIES)).orElseThrow())
                    .satisfies(mine -> {
                        assertThat(mine.amount()).isEqualTo(Money.of("270.00"));
                        assertThat(mine.confidence()).isEqualTo(Confidence.USER_PROVIDED);
                    });
            assertThat(resolver.resolve(ask(SOMEBODY_ELSE, BEIRUT, SpendCategory.GROCERIES))
                            .orElseThrow())
                    .satisfies(theirs -> {
                        assertThat(theirs.amount()).isEqualTo(Money.of("300.00"));
                        assertThat(theirs.confidence()).isEqualTo(Confidence.CROWDSOURCED);
                    });

            contributions.approve(submitted.id());

            ResolvedBaseline nowShared = resolver
                    .resolve(ask(SOMEBODY_ELSE, BEIRUT, SpendCategory.GROCERIES))
                    .orElseThrow();
            assertThat(nowShared.amount()).isEqualTo(Money.of("270.00"));
            assertThat(nowShared.confidence()).isEqualTo(Confidence.CONTRIBUTED);
        }

        /**
         * The validator that works with a single user, and the strongest one: a claimed rent is
         * checked against the user's own detected recurring rent stream. Within ~20% ⇒ corroborated.
         */
        @Test
        void aClaimMatchingTheUsersOwnBankDataIsMarkedCorroborated() {
            Contribution agrees = submit(SpendCategory.RENT, "600.00", "640.00");
            assertThat(agrees.corroboration()).isEqualTo(Corroboration.CORROBORATED);
            assertThat(agrees.state()).isEqualTo(ContributionState.QUEUED);

            Contribution disagrees = submit(SpendCategory.RENT, "600.00", "1000.00");
            assertThat(disagrees.corroboration()).isEqualTo(Corroboration.CONTRADICTED);
            assertThat(disagrees.state()).isEqualTo(ContributionState.REJECTED);

            // Their figure is still theirs. Being unable to corroborate it is not grounds to
            // overrule somebody about their own rent - rent paid in cash leaves no trace.
            assertThat(overrides.find(AUTHOR, SpendCategory.RENT).orElseThrow().amount())
                    .isEqualTo(Money.of("600.00"));

            Contribution unchecked = contributions.submit(
                    AUTHOR,
                    LEBANON,
                    BEIRUT,
                    "Beirut",
                    SpendCategory.RENT,
                    Money.of("600.00"),
                    Optional.empty());
            assertThat(unchecked.corroboration()).isEqualTo(Corroboration.NOT_CHECKED);
            assertThat(unchecked.state()).isEqualTo(ContributionState.REJECTED);
        }

        /** Rent typed into the groceries box breaks the plausible ratio band and is rejected. */
        @Test
        void aFigureOutsideThePlausibleRatioBandIsRejected() {
            // 600 dollars is an ordinary Beirut rent and more than twice any believable Beirut
            // grocery bill. The bank agrees the money was spent - it was, on rent - so this is
            // caught by the band rather than by corroboration.
            Contribution misfiled = submit(SpendCategory.GROCERIES, "600.00", "600.00");

            assertThat(misfiled.state()).isEqualTo(ContributionState.REJECTED);
            assertThat(misfiled.corroboration()).isEqualTo(Corroboration.CORROBORATED);
            assertThat(misfiled.notes()).contains("another box");

            // The same figure in the box it belongs in passes, and so does a believable grocery bill.
            assertThat(submit(SpendCategory.RENT, "600.00", "600.00").state())
                    .isEqualTo(ContributionState.QUEUED);
            assertThat(submit(SpendCategory.GROCERIES, "300.00", "300.00").state())
                    .isEqualTo(ContributionState.QUEUED);
        }

        /** Publication requires passing validation AND explicit approval. Never one alone. */
        @Test
        void nothingBecomesACityDefaultWithoutApproval() {
            BaselineResolver resolver = resolver(TODAY, new NoOfficialData(), cityData);
            Contribution queued = submit(SpendCategory.GROCERIES, "270.00", "265.00");

            assertThat(queued.state()).isEqualTo(ContributionState.QUEUED);
            assertThat(contributions.awaitingApproval()).extracting(Contribution::id)
                    .containsExactly(queued.id());
            assertThat(resolver.resolve(ask(SOMEBODY_ELSE, BEIRUT, SpendCategory.GROCERIES))
                            .orElseThrow()
                            .confidence())
                    .isEqualTo(Confidence.CROWDSOURCED);

            assertThat(contributions.approve(queued.id()).state())
                    .isEqualTo(ContributionState.PUBLISHED);
            assertThat(contributions.awaitingApproval()).isEmpty();

            // And approval is not a way around validation: a rejected figure cannot be waved
            // through, which is exactly where a tired reviewer would otherwise do it.
            Contribution rejected = submit(SpendCategory.GROCERIES, "600.00", "600.00");
            assertThatThrownBy(() -> contributions.approve(rejected.id()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("passed validation");
        }
    }

    /**
     * Both adapters — the committed-CSV one and the JPA one — must satisfy this same specification.
     * Running one suite against both is what proves the port is honest rather than decorative.
     */
    @Test
    void bothProviderImplementationsSatisfyTheSameContract() throws Exception {
        // The suite itself is CostOfLivingProviderContract. The classpath adapter runs it here in
        // the fast tier; the JPA adapter runs it under Failsafe against a real Postgres, because
        // that is the only way to test the adapter that talks to one. This behaviour guards the
        // arrangement: deleting either subclass, or quietly emptying the shared suite, fails here
        // rather than leaving one adapter silently unspecified.
        ClassLoader loader = getClass().getClassLoader();
        Class<?> classpathRunner = Class.forName(
                "com.hasan.budget.costofliving.classpath.ClasspathCostOfLivingProviderTest",
                false,
                loader);
        Class<?> jpaRunner =
                Class.forName(
                        "com.hasan.budget.costofliving.persistence.JpaCostOfLivingProviderIT",
                        false,
                        loader);

        assertThat(classpathRunner.getInterfaces()).contains(CostOfLivingProviderContract.class);
        assertThat(jpaRunner.getInterfaces()).contains(CostOfLivingProviderContract.class);
        assertThat(jpaRunner.getSimpleName())
                .describedAs("the JPA adapter needs a database, so it must run in the Failsafe tier")
                .endsWith("IT");

        long behaviours = java.util.Arrays.stream(CostOfLivingProviderContract.class.getDeclaredMethods())
                .filter(CostOfLivingSpecTest::isABehaviour)
                .count();
        assertThat(behaviours)
                .describedAs("behaviours in the shared provider contract")
                .isGreaterThanOrEqualTo(5);
    }

    private static boolean isABehaviour(Method method) {
        return method.isAnnotationPresent(Test.class)
                || method.isAnnotationPresent(ParameterizedTest.class);
    }
}
