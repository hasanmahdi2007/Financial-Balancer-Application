package com.hasan.budget.costofliving.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface DataSourceRepository extends JpaRepository<DataSourceEntity, String> {}
