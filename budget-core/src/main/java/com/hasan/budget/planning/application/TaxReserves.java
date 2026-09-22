package com.hasan.budget.planning.application;

import com.hasan.budget.shared.Money;
import java.util.Optional;

/**
 * How much a user must hold back for tax each month. Empty for income that arrives already taxed,
 * which is the common case and the one that matters: applying a rate to payroll income subtracts tax
 * a second time.
 */
@FunctionalInterface
public interface TaxReserves {

    Optional<Money> monthlyReserve(PlanningProfile profile, Money monthlyIncome);
}
