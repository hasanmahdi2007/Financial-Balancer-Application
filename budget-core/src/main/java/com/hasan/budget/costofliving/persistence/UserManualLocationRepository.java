package com.hasan.budget.costofliving.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Keyed by user and by nothing else. There is no finder by city label here, and adding one is how
 * fuzzy matching would come back: a typed name must never select a metro.
 */
interface UserManualLocationRepository extends JpaRepository<UserManualLocationEntity, String> {}
