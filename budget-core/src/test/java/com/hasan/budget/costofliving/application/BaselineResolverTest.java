package com.hasan.budget.costofliving.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.costofliving.classpath.ClasspathCostOfLivingProvider;
import com.hasan.budget.costofliving.classpath.ClasspathCountryBaselineSource;
import com.hasan.budget.costofliving.classpath.CuratedDataset;
import com.hasan.budget.costofliving.domain.BaselineQuery;
import com.hasan.budget.costofliving.domain.CityBaselines;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.support.InMemoryUserOverrideStore;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Resolving a whole city at once, which is how the planner will ask.
 *
 * <p>Separate from the single-category behaviours because the set carries a judgement the
 * individual figures do not: how old the weakest of them is, and therefore how much the plan built
 * on all of them is worth.
 */
class BaselineResolverTest {

    private static final MetroId BEIRUT = new MetroId("beirut");

    private final CuratedDataset curated = CuratedDataset.load();
    private final InMemoryUserOverrideStore overrides = new InMemoryUserOverrideStore();

    private static Clock at(String day) {
        return Clock.fixed(Instant.parse(day + "T00:00:00Z"), ZoneOffset.UTC);
    }

    private BaselineResolver resolverAt(Clock clock) {
        return new BaselineResolver(List.of(
                new UserOverrideLayer(overrides),
                new CityDataLayer(Confidence.OFFICIAL, new NoOfficialData()),
                new CityDataLayer(
                        Confidence.CROWDSOURCED, new ClasspathCostOfLivingProvider(curated, clock)),
                new CountryEstimateLayer(new ClasspathCountryBaselineSource(curated, clock))));
    }

    private BaselineQuery beirut() {
        return new BaselineQuery(
                "user-a", CountryCode.LEBANON, BEIRUT, SpendCategory.RENT, IncomeQuintile.Q3);
    }

    @Test
    @DisplayName("a city comes back as one set, dated by the weakest figure in it")
    void aCityResolvesAsOneSetDatedByItsOldestFigure() {
        CityBaselines beirut = resolverAt(at("2026-09-20")).cityBaselines(beirut());

        assertThat(beirut.metro()).isEqualTo(BEIRUT);
        assertThat(beirut.baselines()).containsKeys(SpendCategory.RENT, SpendCategory.GROCERIES);
        // Reporting the freshest date instead would let one recent figure vouch for nine old ones.
        assertThat(beirut.oldestAsOf()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(beirut.hasStaleFigures()).isFalse();
    }

    @Test
    @DisplayName("once the set has drifted, the whole city is worth flagging rather than one figure")
    void aCityWhoseFiguresHaveDriftedReportsItself() {
        // Eleven months at Lebanon's 17.3% a year takes every figure in the set past the threshold
        // together, because they were all gathered on the same day. The flag belongs on the set:
        // a plan is only as current as the worst number in it.
        CityBaselines beirut = resolverAt(at("2027-08-20")).cityBaselines(beirut());

        assertThat(beirut.hasStaleFigures()).isTrue();
        assertThat(beirut.oldestAsOf()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    @DisplayName("a user with no listed city is asked for categories, not for a city set")
    void aUserWithNoListedCityCannotBeAskedForACitySet() {
        // Their country still resolves every category - that is what the estimate layer is for -
        // but there is no city to attach the set to, and inventing one would put a name on figures
        // that are not that city's.
        BaselineQuery manual = new BaselineQuery(
                "user-a", CountryCode.LEBANON, null, SpendCategory.RENT, IncomeQuintile.Q3);
        BaselineResolver resolver = resolverAt(at("2026-09-20"));

        assertThatThrownBy(() -> resolver.cityBaselines(manual))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no listed city");
        assertThat(resolver.resolveAll(manual))
                .describedAs("every category still resolves for them")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a resolver with no layers is refused rather than silently answering nothing")
    void aResolverMustHaveAtLeastOneLayer() {
        assertThatThrownBy(() -> new BaselineResolver(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
