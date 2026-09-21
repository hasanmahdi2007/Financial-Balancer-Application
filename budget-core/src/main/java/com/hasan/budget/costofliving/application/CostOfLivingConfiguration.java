package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.port.CityDirectory;
import com.hasan.budget.costofliving.port.ContributionStore;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import com.hasan.budget.costofliving.port.CountryBaselineSource;
import com.hasan.budget.costofliving.port.UserLocationStore;
import com.hasan.budget.costofliving.port.UserOverrideStore;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the resolution order is decided, and the only place it appears.
 *
 * <p>The list in {@link #baselineResolver} is the precedence rule in full. Reading it top to bottom
 * tells you which number wins without opening anything else, and adding the deferred official source
 * means replacing one argument rather than editing a method full of branches.
 */
@Configuration
class CostOfLivingConfiguration {

    /**
     * Adapters need today's date; domain code never does. Injected so a test can stand at any point
     * in time and watch a figure age, and shared rather than owned so the whole application agrees
     * on what "now" is.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The chain, in order.
     *
     * <p>The official layer is wired to {@link NoOfficialData} and answers nothing. That is
     * deliberate: v1 ships curated figures only, and keeping the empty slot occupied is what makes
     * reviving the deferred BLS/BEA pipeline a new class in this one argument rather than a
     * rethink of where it belongs.
     */
    @Bean
    BaselineResolver baselineResolver(
            CostOfLivingProvider cityData,
            CountryBaselineSource countryBaselines,
            UserOverrideStore overrides) {
        return new BaselineResolver(List.of(
                new UserOverrideLayer(overrides),
                new CityDataLayer(Confidence.OFFICIAL, new NoOfficialData()),
                new CityDataLayer(Confidence.CONTRIBUTED, cityData),
                new CityDataLayer(Confidence.CROWDSOURCED, cityData),
                new CountryEstimateLayer(countryBaselines)));
    }

    @Bean
    CityCatalogService cityCatalogService(
            CityDirectory directory,
            CountryBaselineSource countryBaselines,
            UserLocationStore locations) {
        return new CityCatalogService(directory, countryBaselines, locations);
    }

    @Bean
    UserFigureService userFigureService(
            UserOverrideStore overrides, CityDirectory directory, Clock clock) {
        return new UserFigureService(overrides, directory, clock);
    }

    @Bean
    ContributionValidator contributionValidator(
            CountryBaselineSource countryBaselines, UserFigureService userFigures) {
        return new ContributionValidator(countryBaselines, userFigures);
    }

    @Bean
    ContributionService contributionService(
            ContributionStore store,
            ContributionValidator validator,
            UserFigureService userFigures,
            Clock clock) {
        return new ContributionService(store, validator, userFigures, clock);
    }
}
