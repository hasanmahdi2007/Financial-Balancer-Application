package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.shared.SpendCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CityCategoryBaselineRepository extends JpaRepository<CityCategoryBaselineEntity, Long> {

    List<CityCategoryBaselineEntity> findByMetroId(Long metroId);

    List<CityCategoryBaselineEntity> findByMetroIdAndCategory(Long metroId, SpendCategory category);

    /**
     * Used when a contribution is published, to replace any earlier contributed figure for the same
     * cell rather than collide with the unique index. Restricted to one confidence on purpose: a
     * published contribution must never be able to delete a curated or official row.
     */
    void deleteByMetroIdAndCategoryAndConfidence(
            Long metroId, SpendCategory category, Confidence confidence);
}
