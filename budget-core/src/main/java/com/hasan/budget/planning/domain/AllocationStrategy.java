package com.hasan.budget.planning.domain;

/**
 * Splits a monthly surplus across competing savings goals.
 *
 * <p>Implementations must be pure: no framework dependencies, no I/O, no clock access. The
 * evaluation date arrives on the request so results are reproducible.
 */
public interface AllocationStrategy {

    AllocationResult allocate(AllocationRequest request);
}
