package com.hasan.budget.costofliving.classpath;

import com.hasan.budget.costofliving.contract.CostOfLivingProviderContract;
import com.hasan.budget.costofliving.port.CostOfLivingProvider;
import java.time.Clock;

/**
 * The committed-CSV adapter against the shared provider specification.
 *
 * <p>Deliberately a {@code *Test} and not an {@code *IT}: this half of the pair needs no Docker and
 * no database, which is what keeps the whole cost-of-living specification runnable on a machine with
 * the network down.
 */
class ClasspathCostOfLivingProviderTest implements CostOfLivingProviderContract {

    @Override
    public CostOfLivingProvider providerAt(Clock clock) {
        return new ClasspathCostOfLivingProvider(CuratedDataset.load(), clock);
    }
}
