package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.application.BaselineResolver;
import com.hasan.budget.costofliving.application.CityCatalogService;
import com.hasan.budget.planning.domain.AllocationStrategy;
import com.hasan.budget.planning.domain.GreedyPriorityAllocator;
import com.hasan.budget.profile.application.DiscretionaryFloorService;
import com.hasan.budget.profile.application.TaxRateResolver;
import com.hasan.budget.profile.domain.TaxReserve;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the planning module. The domain classes stay free of Spring; this is the one place they meet
 * it, and the one place the allocation strategy is chosen - which is what lets a future
 * linear-programming allocator replace the greedy one as a single-line change here.
 */
@Configuration
public class PlanningConfiguration {

    @Bean
    AllocationStrategy allocationStrategy() {
        return new GreedyPriorityAllocator();
    }

    @Bean
    PlanAssembler planAssembler(AllocationStrategy allocator, DiscretionaryFloorService floors) {
        return new PlanAssembler(allocator, floors::floorFor);
    }

    @Bean
    Places places(CityCatalogService catalogue, BaselineResolver resolver) {
        return new CostOfLivingPlaces(catalogue, resolver);
    }

    /**
     * Tax held back only for income that arrives untaxed. The decision is made by the profile module's
     * {@code TaxTreatment}, and nowhere here, because applying a rate to payroll income would subtract
     * tax twice.
     */
    @Bean
    TaxReserves taxReserves(TaxRateResolver rates) {
        return (profile, income) -> rates.treatmentFor(profile.asUserProfile())
                .flatMap(treatment -> treatment.monthlyReserve(income))
                .map(TaxReserve::monthlyAmount);
    }

    @Bean
    PlanService planService(
            PlanningProfileStore profiles,
            SpendingStore spending,
            GoalStore goals,
            PlanSnapshotStore snapshots,
            Places places,
            TaxReserves taxReserves,
            PlanAssembler assembler,
            Clock clock) {
        return new PlanService(
                profiles, spending, goals, snapshots, places, taxReserves, assembler, clock,
                () -> UUID.randomUUID().toString());
    }

    @Bean
    QuestionService questionService(PlanService plans, Places places) {
        return new QuestionService(plans, places);
    }

    @Bean
    DecisionService decisionService(PlanService plans, Clock clock) {
        return new DecisionService(plans, clock);
    }
}
