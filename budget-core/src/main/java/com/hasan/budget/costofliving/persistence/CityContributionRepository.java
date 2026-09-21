package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.ContributionState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CityContributionRepository extends JpaRepository<CityContributionEntity, Long> {

    List<CityContributionEntity> findByValidationStateOrderBySubmittedAt(ContributionState state);
}
