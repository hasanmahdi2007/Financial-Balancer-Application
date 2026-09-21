package com.hasan.budget.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The double subtraction is invisible in testing - every number still adds up - and shows only as a
 * plan that is inexplicably pessimistic. These tests are the thing standing between the product and
 * that bug.
 */
class TaxTreatmentTest {

    private static final LocalDate SEEDED_ON = LocalDate.of(2025, 1, 1);
    private static final ResolvedTaxRate FIFTEEN_PERCENT = ResolvedTaxRate.estimatedForCountry(
            Rate.ofPercent("15"), "a seeded country estimate", SEEDED_ON);

    /**
     * However high the rate, an already-taxed income produces no reserve. The rate's size must have
     * no bearing on this: a condition that only held for small rates would be a latent bug.
     */
    @ParameterizedTest
    @EnumSource(value = Confidence.class, names = {"USER_PROVIDED", "ESTIMATED"})
    void alreadyTaxedIncomeNeverProducesAReserveAtAnyRate(Confidence confidence) {
        ResolvedTaxRate steep =
                new ResolvedTaxRate(Rate.ofPercent("45"), confidence, "whatever the source", SEEDED_ON);

        assertThat(new TaxTreatment(true, steep).monthlyReserve(Money.of(4_000))).isEmpty();
    }

    /** The reserve is the resolved rate applied to income, and nothing more elaborate than that. */
    @Test
    void anUntaxedIncomeReservesTheResolvedShareOfIt() {
        TaxTreatment freelancing = new TaxTreatment(false, FIFTEEN_PERCENT);

        assertThat(freelancing.monthlyReserve(Money.of(4_000)).orElseThrow().monthlyAmount())
                .isEqualTo(Money.of(600));
        assertThat(freelancing.monthlyReserve(Money.of(2_750)).orElseThrow().monthlyAmount())
                .isEqualTo(Money.of("412.50"));
    }

    /**
     * The one thing an employed user's rate is for. Grossing up explains the gap between the salary
     * they negotiated and the money that arrives; it never narrows it.
     */
    @Test
    void anEmployedUsersRateOnlyEverExplainsTheGrossToNetGap() {
        TaxTreatment employed = new TaxTreatment(true, FIFTEEN_PERCENT);

        assertThat(employed.impliedGrossIncome(Money.of(4_000))).isEqualTo(Money.of("4705.88"));
        assertThat(employed.monthlyReserve(Money.of(4_000))).isEmpty();
    }

    /** A rate that took everything would leave a plan with no income to allocate at all. */
    @Test
    void aRateOfOneHundredPercentIsRejected() {
        assertThatThrownBy(() -> ResolvedTaxRate.estimatedForCountry(
                        Rate.ofPercent("100"), "a source", SEEDED_ON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leave nothing");
    }

    /**
     * A single effective percentage approximates one person's position in a progressive system. It
     * may be their own figure or our estimate, and it may never wear the badge of a statistic.
     */
    @ParameterizedTest
    @EnumSource(value = Confidence.class, names = {"OFFICIAL", "CONTRIBUTED", "CROWDSOURCED"})
    void aRateCannotClaimToBeAStatistic(Confidence notAvailableToARate) {
        assertThatThrownBy(() -> new ResolvedTaxRate(
                        Rate.ofPercent("15"), notAvailableToARate, "a source", SEEDED_ON))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The two labels a rate may carry, and what each one means about where it came from. */
    @Test
    void aRateIsLabelledByWhoProvidedIt() {
        assertThat(FIFTEEN_PERCENT.confidence()).isEqualTo(Confidence.ESTIMATED);
        assertThat(ResolvedTaxRate.statedByUser(Rate.ofPercent("22"), SEEDED_ON).confidence())
                .isEqualTo(Confidence.USER_PROVIDED);
    }
}
