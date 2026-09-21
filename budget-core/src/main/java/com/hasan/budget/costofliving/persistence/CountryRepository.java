package com.hasan.budget.costofliving.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CountryRepository extends JpaRepository<CountryEntity, String> {

    List<CountryEntity> findByListedTrueOrderByName();
}
