package com.hasan.budget.profile.application;

import com.hasan.budget.profile.persistence.JdbcCountryTaxRateLayer;
import com.hasan.budget.profile.persistence.JdbcUserTaxRateLayer;
import com.hasan.budget.profile.port.FloorPolicySource;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the profile module's services, and states the tax chain's precedence in one place.
 *
 * <p>The layer order is written out here rather than left to bean discovery. Precedence is the
 * whole behaviour of a resolution chain, and having it depend on classpath scanning order would
 * make "the user's own figure wins" a property nobody could point at.
 */
@Configuration
public class ProfileConfiguration {

    @Bean
    TaxRateResolver taxRateResolver(JdbcUserTaxRateLayer typedByTheUser, JdbcCountryTaxRateLayer seeded) {
        return new TaxRateResolver(List.of(typedByTheUser, seeded));
    }

    @Bean
    DiscretionaryFloorService discretionaryFloorService(FloorPolicySource policySource) {
        return new DiscretionaryFloorService(policySource);
    }
}
