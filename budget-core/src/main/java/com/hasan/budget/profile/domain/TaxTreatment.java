package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * What tax means for one user's plan, decided by a single onboarding boolean.
 *
 * <p>This type exists to contain one specific bug. Payroll deposits are <em>already net of tax</em>,
 * and this product collects net income, so applying a rate on top of that figure subtracts tax a
 * second time and quietly removes a fifth of money the user actually has. The mistake is invisible
 * in testing - every number still adds up - and only shows as a plan that is inexplicably
 * pessimistic. So the decision is made once, here, and no caller is offered a way to apply the rate
 * themselves.
 *
 * <p>For an employed user the resolved rate is therefore not unused, but it is only ever
 * explanatory: it answers "what was I earning before tax?" and never enters the arithmetic.
 */
public record TaxTreatment(boolean incomeArrivesTaxed, ResolvedTaxRate rate) {

    public TaxTreatment {
        Objects.requireNonNull(rate, "rate");
    }

    public static TaxTreatment forProfile(UserProfile profile, ResolvedTaxRate rate) {
        Objects.requireNonNull(profile, "profile");
        return new TaxTreatment(profile.incomeArrivesTaxed(), rate);
    }

    /**
     * The monthly reserve, or empty for income that arrives already taxed.
     *
     * <p>Empty is the common case and the important one: no tax line at all, rather than a tax line
     * of zero. A zero line invites someone to "fix" it later by populating it.
     */
    public Optional<TaxReserve> monthlyReserve(Money monthlyIncome) {
        Objects.requireNonNull(monthlyIncome, "monthlyIncome");
        if (incomeArrivesTaxed) {
            return Optional.empty();
        }
        return Optional.of(new TaxReserve(rate.rate().applyTo(monthlyIncome)));
    }

    /**
     * The pre-tax figure the user's net income implies, for explaining the gross-to-net gap when
     * they ask about it. Explanatory only - nothing is ever subtracted from this.
     */
    public Money impliedGrossIncome(Money netMonthlyIncome) {
        return rate.rate().grossUpFrom(netMonthlyIncome);
    }
}
