package com.hasan.budget.costofliving.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.costofliving.domain.CityBaselines;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.Staleness;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * One specification, run against every implementation of {@link CostOfLivingProvider}.
 *
 * <p>This is what makes the port honest rather than decorative. An interface with one implementation
 * is a naming convention; two implementations held to the same suite is a seam you can actually
 * replace something through. The committed-CSV adapter runs it in the fast tier and the Postgres
 * adapter runs it against a real database, so a behaviour that quietly depends on either fails here
 * rather than in front of a user.
 *
 * <p>Both adapters are fed by the same committed CSVs - the Flyway migration inserts the very files
 * the classpath adapter reads - so the expected figures below are properties of the seed data, not
 * of either adapter.
 */
public interface CostOfLivingProviderContract {

    /** The day the curated figures were gathered for Lebanon, so nothing has aged yet. */
    Clock AT_CURATION =
            Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);

    /** Nine months later, by which point the two countries have diverged sharply. */
    Clock NINE_MONTHS_ON =
            Clock.fixed(Instant.parse("2027-06-20T00:00:00Z"), ZoneOffset.UTC);

    MetroId BEIRUT = new MetroId("beirut");
    MetroId WICHITA = new MetroId("wichita");

    /** The adapter under test, reading today's date from the given clock. */
    CostOfLivingProvider providerAt(Clock clock);

    default CostOfLivingProvider provider() {
        return providerAt(AT_CURATION);
    }

    @Test
    @DisplayName("a figure arrives with its amount, its confidence, its source and its date")
    default void aFigureCarriesEverythingNeededToJudgeIt() {
        ResolvedBaseline rent =
                provider().baselineFor(BEIRUT, SpendCategory.RENT, IncomeQuintile.Q3).orElseThrow();

        assertThat(rent.amount()).isEqualTo(Money.of("600.00"));
        assertThat(rent.confidence()).isEqualTo(Confidence.CROWDSOURCED);
        assertThat(rent.sourceName()).isNotBlank();
        assertThat(rent.asOf()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    @DisplayName("nothing the curated data holds is labelled as official statistics")
    default void noCuratedFigureClaimsToBeOfficial() {
        for (MetroId metro : new MetroId[] {BEIRUT, WICHITA}) {
            CityBaselines all = provider().baselinesFor(metro, IncomeQuintile.Q3);
            assertThat(all.baselines().values())
                    .describedAs("curated figures for %s", metro.slug())
                    .isNotEmpty()
                    .allSatisfy(baseline ->
                            assertThat(baseline.confidence()).isEqualTo(Confidence.CROWDSOURCED));
        }
    }

    @Test
    @DisplayName("a city we hold nothing for answers empty, so a later layer can answer instead")
    default void anUnknownCityAnswersEmptyRatherThanZero() {
        Optional<ResolvedBaseline> nothing = provider()
                .baselineFor(new MetroId("atlantis"), SpendCategory.RENT, IncomeQuintile.Q3);

        assertThat(nothing).isEmpty();
    }

    @Test
    @DisplayName("a category with no figure answers empty rather than inventing one")
    default void aCategoryWithNoFigureAnswersEmpty() {
        // Loan instalments are set by a contract, not by what a city charges, so no source has a
        // figure for them. Answering zero here would subtract a real commitment from the plan.
        Optional<ResolvedBaseline> nothing =
                provider().baselineFor(BEIRUT, SpendCategory.DEBT_PAYMENT, IncomeQuintile.Q3);

        assertThat(nothing).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(IncomeQuintile.class)
    @DisplayName("a figure recorded without an income quintile answers for every quintile")
    default void aFigureWithNoQuintileAnswersForEveryone(IncomeQuintile quintile) {
        // Every curated row leaves income_quintile null, because these figures are not split by
        // income. Resolution has to keep working through that, since the column only starts being
        // filled in if the deferred official pipeline is ever revived.
        ResolvedBaseline rent =
                provider().baselineFor(BEIRUT, SpendCategory.RENT, quintile).orElseThrow();

        assertThat(rent.amount()).isEqualTo(Money.of("600.00"));
    }

    @Test
    @DisplayName("a whole city resolves as one set, dated by its oldest figure")
    default void aCityResolvesAsOneSetDatedByItsOldestFigure() {
        CityBaselines beirut = provider().baselinesFor(BEIRUT, IncomeQuintile.Q3);

        assertThat(beirut.metro()).isEqualTo(BEIRUT);
        assertThat(beirut.baselines()).containsKeys(
                SpendCategory.RENT, SpendCategory.GROCERIES, SpendCategory.UTILITIES);
        assertThat(beirut.forCategory(SpendCategory.GROCERIES)).isPresent();
        assertThat(beirut.oldestAsOf()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    @DisplayName("the same age means different staleness in a high- and a low-inflation country")
    default void inflationRatherThanAgeDecidesWhenAFigureGoesStale() {
        CostOfLivingProvider later = providerAt(NINE_MONTHS_ON);

        ResolvedBaseline beirut =
                later.baselineFor(BEIRUT, SpendCategory.RENT, IncomeQuintile.Q3).orElseThrow();
        ResolvedBaseline wichita =
                later.baselineFor(WICHITA, SpendCategory.RENT, IncomeQuintile.Q3).orElseThrow();

        // Beirut's figure is nine months old against 17.3% a year; Wichita's is seventeen months old
        // against 3%. The older figure is the fresher one, which is the entire point of deriving
        // staleness from inflation rather than from a fixed expiry.
        assertThat(beirut.staleness()).isEqualTo(Staleness.AGING);
        assertThat(wichita.staleness()).isEqualTo(Staleness.FRESH);
        assertThat(wichita.asOf()).isBefore(beirut.asOf());
    }

    @Test
    @DisplayName("an aged figure is flagged, never quietly adjusted")
    default void aStaleFigureKeepsItsStoredAmount() {
        ResolvedBaseline now =
                provider().baselineFor(BEIRUT, SpendCategory.RENT, IncomeQuintile.Q3).orElseThrow();
        ResolvedBaseline later = providerAt(NINE_MONTHS_ON)
                .baselineFor(BEIRUT, SpendCategory.RENT, IncomeQuintile.Q3)
                .orElseThrow();

        assertThat(later.amount()).isEqualTo(now.amount());
        assertThat(later.staleness()).isNotEqualTo(now.staleness());
    }
}
