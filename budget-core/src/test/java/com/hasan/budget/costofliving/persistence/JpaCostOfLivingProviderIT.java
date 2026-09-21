package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.contract.CostOfLivingProviderContract;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Postgres adapter against the same shared provider specification.
 *
 * <p>The figures it is checked against come from the committed CSVs, inserted by the Flyway
 * migration, which is the same data the classpath adapter reads straight off the classpath. So a
 * disagreement between the two adapters here is a genuine behavioural difference rather than two
 * copies of the seed data having drifted apart.
 *
 * <p>An {@code *IT} because it needs Docker: Failsafe runs it, skipped locally unless
 * {@code -DskipITs=false} and always run in CI.
 */
class JpaCostOfLivingProviderIT extends CostOfLivingDatabaseFixture
        implements CostOfLivingProviderContract {

    @Autowired
    private MetroAreaRepository metros;

    @Autowired
    private CityCategoryBaselineRepository baselines;

    @Autowired
    private CountryRepository countries;

    @Autowired
    private DataSourceRepository sources;

    /**
     * Built by hand rather than injected, because the contract needs to stand at several points in
     * time and the container's bean holds one clock. Same class, same repositories, different
     * "today".
     */
    @Override
    public CostOfLivingProvider providerAt(Clock clock) {
        return new JpaCostOfLivingProvider(metros, baselines, countries, sources, clock);
    }
}
