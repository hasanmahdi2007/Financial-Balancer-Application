package com.hasan.budget.costofliving.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Note what is absent: any finder taking a display name. Cities are looked up by slug and by
 * nothing else, which is what keeps a typed city name from ever almost-matching the wrong metro.
 */
interface MetroAreaRepository extends JpaRepository<MetroAreaEntity, Long> {

    Optional<MetroAreaEntity> findBySlug(String slug);

    List<MetroAreaEntity> findByCountryCodeOrderBySortRank(String countryCode);
}
