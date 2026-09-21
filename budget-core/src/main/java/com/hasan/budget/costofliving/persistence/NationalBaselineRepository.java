package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.shared.SpendCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface NationalBaselineRepository extends JpaRepository<NationalBaselineEntity, Long> {

    List<NationalBaselineEntity> findByCountryCode(String countryCode);

    List<NationalBaselineEntity> findByCountryCodeAndCategory(
            String countryCode, SpendCategory category);
}
