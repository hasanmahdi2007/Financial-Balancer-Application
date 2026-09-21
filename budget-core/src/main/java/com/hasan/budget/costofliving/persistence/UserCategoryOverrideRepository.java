package com.hasan.budget.costofliving.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserCategoryOverrideRepository
        extends JpaRepository<UserCategoryOverrideEntity, UserCategoryOverrideEntity.Key> {

    List<UserCategoryOverrideEntity> findByUserId(String userId);
}
