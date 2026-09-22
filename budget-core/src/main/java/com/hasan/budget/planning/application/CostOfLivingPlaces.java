package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.application.BaselineResolver;
import com.hasan.budget.costofliving.application.CityCatalogService;
import com.hasan.budget.costofliving.domain.BaselineQuery;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** {@link Places}, answered by the cost-of-living module's own services. */
final class CostOfLivingPlaces implements Places {

    private final CityCatalogService catalogue;
    private final BaselineResolver resolver;

    CostOfLivingPlaces(CityCatalogService catalogue, BaselineResolver resolver) {
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Optional<String> countryName(CountryCode country) {
        return catalogue.countries().stream()
                .filter(listed -> listed.code().equals(country))
                .map(CountryProfile::name)
                .findFirst();
    }

    @Override
    public Optional<City> city(CountryCode country, MetroId city) {
        return catalogue.city(city)
                .filter(listing -> listing.country().equals(country))
                .map(listing -> new City(
                        listing.metro(),
                        listing.displayName(),
                        listing.confidence().label(),
                        listing.confidence().meaning(),
                        listing.asOf()));
    }

    @Override
    public void recordCityNotListed(String userId, CountryCode country, String cityName) {
        catalogue.recordManualLocation(userId, country, cityName);
    }

    @Override
    public Map<SpendCategory, ResolvedBaseline> baselinesFor(PlanningProfile profile) {
        // Any category will do to start the query; resolveAll walks every category from it.
        return resolver.resolveAll(new BaselineQuery(
                profile.userId(),
                profile.country(),
                profile.city(),
                SpendCategory.RENT,
                PlanningProfile.UNUSED_BY_CURATED_FIGURES));
    }
}
