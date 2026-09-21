package com.hasan.budget.costofliving.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.costofliving.classpath.CuratedDataset;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.Contribution;
import com.hasan.budget.costofliving.domain.ContributionState;
import com.hasan.budget.costofliving.domain.Corroboration;
import com.hasan.budget.costofliving.domain.ManualLocation;
import com.hasan.budget.costofliving.domain.UserOverride;
import com.hasan.budget.costofliving.port.CityDirectory;
import com.hasan.budget.costofliving.port.ContributionStore;
import com.hasan.budget.costofliving.port.CountryBaselineSource;
import com.hasan.budget.costofliving.port.UserLocationStore;
import com.hasan.budget.costofliving.port.UserOverrideStore;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The schema itself, applied from empty against the Postgres this project actually ships.
 *
 * <p>The behaviours here are the ones only a real database can answer. Whether a partial unique
 * index really permits many rows with a null quintile, whether a check constraint really refuses a
 * mislabelled figure, and whether Hibernate agrees the entities match the migrations - none of those
 * can be established anywhere else, and every one of them would fail silently in production.
 *
 * <p>Transactional, so each behaviour rolls back and the seeded counts other behaviours assert on
 * stay exactly as the migration left them.
 */
@Transactional
class CostOfLivingSchemaIT extends CostOfLivingDatabaseFixture {

    private static final MetroId BEIRUT = new MetroId("beirut");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CityDirectory directory;

    @Autowired
    private CountryBaselineSource countryBaselines;

    @Autowired
    private UserOverrideStore overrides;

    @Autowired
    private ContributionStore contributions;

    @Autowired
    private UserLocationStore userLocations;

    @Test
    @DisplayName("the migrations apply from empty and load every committed figure")
    void everyCommittedFigureSurvivesTheMigration() {
        // The context starting at all means Flyway ran from nothing and Hibernate's validation
        // agreed the entities match the migrated schema, since ddl-auto is validate. What is left
        // to check is that the seeding inserted the committed files rather than part of them.
        CuratedDataset committed = CuratedDataset.load();

        assertThat(count("city_category_baseline")).isEqualTo(committed.cityFigures().size());
        assertThat(count("national_baseline")).isEqualTo(committed.countryFigures().size());
        assertThat(count("metro_area")).isEqualTo(committed.metros().size());
        assertThat(count("country")).isEqualTo(committed.countries().size());
        assertThat(count("data_source")).isEqualTo(committed.sources().size());
    }

    @Test
    @DisplayName("many rows may share a null income quintile, which the curated data depends on")
    void theNullQuintileColumnIsNotFoldedIntoAKey() {
        // Every curated row leaves the quintile null. Had it been folded into a primary key - the
        // obvious thing to do - Postgres would have refused the second row, and reviving the
        // deferred official pipeline would need a migration and a backfill rather than a new class.
        assertThat(countWhere("city_category_baseline", "income_quintile IS NULL"))
                .isEqualTo(count("city_category_baseline"))
                .isGreaterThan(1);
        assertThat(countWhere("national_baseline", "income_quintile IS NULL"))
                .isEqualTo(count("national_baseline"))
                .isGreaterThan(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ESTIMATED", "USER_PROVIDED", "RESEARCHED"})
    @DisplayName("the database refuses to store a city figure under a tier that is not a city tier")
    void aCityFigureCannotBeMislabelled(String tier) {
        // The last line of defence for the confidence model. ESTIMATED is a country-level fallback
        // and USER_PROVIDED belongs to one person; a row of either kind sitting in the shared city
        // table would bypass the resolver's layers entirely, and a guess would end up wearing
        // whichever badge somebody typed.
        assertThatThrownBy(() -> insertBeirutHealthcareFigure(tier))
                .hasStackTraceContaining("city_category_baseline");
    }

    @Test
    @DisplayName("every seeded city figure is labelled as researched rather than official")
    void nothingSeededClaimsToBeOfficial() {
        assertThat(countWhere("city_category_baseline", "confidence = 'OFFICIAL'")).isZero();
        assertThat(countWhere("city_category_baseline", "confidence = 'CROWDSOURCED'"))
                .isEqualTo(count("city_category_baseline"));
    }

    @Test
    @DisplayName("the catalogue and the country estimates read back through their ports")
    void theCatalogueAndEstimatesReadBackCorrectly() {
        assertThat(directory.listedCountries()).extracting(c -> c.code().value()).contains("LB", "US");
        assertThat(directory.citiesIn(CountryCode.LEBANON))
                .extracting(c -> c.metro().slug())
                .contains("beirut", "tripoli-lb");
        assertThat(directory.city(new MetroId("Beirut")))
                .describedAs("a typed city name is never a lookup key, not even a well-spelled one")
                .isEmpty();
        assertThat(countryBaselines
                        .countryBaseline(CountryCode.LEBANON, SpendCategory.RENT)
                        .orElseThrow()
                        .amount())
                .isEqualTo(Money.of("350.00"));
        assertThat(countryBaselines.plausibleBand(CountryCode.LEBANON, SpendCategory.GROCERIES))
                .isPresent();
    }

    @Test
    @DisplayName("a user's own figure is stored privately and read back only for them")
    void aUserFigureIsPrivateToItsAuthor() {
        overrides.save(new UserOverride(
                "user-a",
                SpendCategory.GROCERIES,
                Money.of("250.00"),
                LocalDate.parse("2026-09-20"),
                Corroboration.CORROBORATED));

        assertThat(overrides.find("user-a", SpendCategory.GROCERIES)).isPresent();
        assertThat(overrides.find("user-b", SpendCategory.GROCERIES)).isEmpty();
        assertThat(overrides.findAll("user-b")).isEmpty();
    }

    @Test
    @DisplayName("a typed city name is stored to show back, and is still not a lookup key")
    void aManualLocationKeepsTheLabelWithoutMakingItAKey() {
        userLocations.save(new ManualLocation("user-a", CountryCode.LEBANON, "Bcharre"));

        assertThat(userLocations.find("user-a").orElseThrow().cityLabel()).isEqualTo("Bcharre");
        assertThat(userLocations.find("user-b")).isEmpty();
        assertThat(directory.city(new MetroId("Bcharre")))
                .describedAs("storing what somebody typed must not make it findable as a metro")
                .isEmpty();
    }

    @Test
    @DisplayName("publishing writes the contribution and the city default together")
    void publishingWritesBothTables() {
        Contribution queued = contributions.save(new Contribution(
                Contribution.UNSAVED,
                "user-a",
                CountryCode.LEBANON,
                BEIRUT,
                "Beirut",
                SpendCategory.GROCERIES,
                Money.of("270.00"),
                LocalDate.parse("2026-09-20"),
                ContributionState.QUEUED,
                Corroboration.CORROBORATED,
                "Your own transactions agree with this figure."));

        assertThat(contributions.queued()).extracting(Contribution::id).contains(queued.id());

        Contribution published = contributions.publish(queued.approved());

        assertThat(published.state()).isEqualTo(ContributionState.PUBLISHED);
        assertThat(published.metro()).isEqualTo(BEIRUT);
        assertThat(contributions.queued()).extracting(Contribution::id).doesNotContain(queued.id());
        assertThat(countWhere(
                        "city_category_baseline",
                        "confidence = '" + Confidence.CONTRIBUTED.name() + "'"))
                .describedAs("the published figure is now a city default")
                .isEqualTo(1);
    }

    private long count(String table) {
        return countWhere(table, "TRUE");
    }

    private long countWhere(String table, String predicate) {
        return ((Number) entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)
                        .getSingleResult())
                .longValue();
    }

    private void insertBeirutHealthcareFigure(String confidence) {
        Number metroId = (Number) entityManager
                .createNativeQuery("SELECT id FROM metro_area WHERE slug = 'beirut'")
                .getSingleResult();
        entityManager
                .createNativeQuery("INSERT INTO city_category_baseline "
                        + "(metro_id, category, monthly_amount, confidence, source_id, as_of) "
                        + "VALUES (?1, 'HEALTHCARE', 1.00, ?2, 'curated-lb-2026', DATE '2026-09-01')")
                .setParameter(1, metroId.longValue())
                .setParameter(2, confidence)
                .executeUpdate();
        entityManager.flush();
    }
}
