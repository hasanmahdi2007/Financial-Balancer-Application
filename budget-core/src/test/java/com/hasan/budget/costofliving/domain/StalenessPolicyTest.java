package com.hasan.budget.costofliving.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The drift formula on its own, at the thresholds where it decides something.
 *
 * <p>Worth testing separately from the adapters because the interesting behaviour is entirely in the
 * boundaries, and because it is the piece that proves one column can make the same code behave
 * oppositely in two countries.
 */
class StalenessPolicyTest {

    private static final int UNITED_STATES = 300; // 3.00% a year, in basis points
    private static final int LEBANON = 1730; // 17.30% a year

    private static final LocalDate GATHERED = LocalDate.parse("2026-01-01");

    @Test
    @DisplayName("drift is the country's inflation scaled by how long the figure has been sitting")
    void driftIsInflationTimesAge() {
        // Six months at 3% a year is 1.5%, which is the whole formula and the whole of what the
        // thresholds are applied to.
        assertThat(StalenessPolicy.driftPercent(GATHERED, GATHERED.plusMonths(6), UNITED_STATES))
                .isEqualByComparingTo("1.50");
        assertThat(StalenessPolicy.driftPercent(GATHERED, GATHERED.plusMonths(12), LEBANON))
                .isEqualByComparingTo("17.30");
    }

    @Test
    @DisplayName("a low-inflation country keeps a figure fresh for well over a year")
    void theUnitedStatesStaysFreshForNearlyTwentyMonths() {
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED.plusMonths(19), UNITED_STATES))
                .isEqualTo(Staleness.FRESH);
        // Twenty months is exactly 5% drift, which is where fresh ends.
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED.plusMonths(20), UNITED_STATES))
                .isEqualTo(Staleness.AGING);
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED.plusMonths(59), UNITED_STATES))
                .isEqualTo(Staleness.AGING);
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED.plusMonths(60), UNITED_STATES))
                .isEqualTo(Staleness.STALE);
    }

    @Test
    @DisplayName("a high-inflation country reaches the same thresholds within months")
    void lebanonAgesInMonthsRatherThanYears() {
        // Same code, same thresholds, opposite behaviour - driven entirely by one column.
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED.plusMonths(3), LEBANON))
                .isEqualTo(Staleness.FRESH);
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED.plusMonths(4), LEBANON))
                .isEqualTo(Staleness.AGING);
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED.plusMonths(10), LEBANON))
                .isEqualTo(Staleness.AGING);
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED.plusMonths(11), LEBANON))
                .isEqualTo(Staleness.STALE);
    }

    @Test
    @DisplayName("a figure gathered today has not drifted")
    void afigureGatheredTodayIsFresh() {
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED, LEBANON)).isEqualTo(Staleness.FRESH);
        assertThat(StalenessPolicy.ageInMonths(GATHERED, GATHERED)).isZero();
    }

    @Test
    @DisplayName("a figure dated in the future is treated as new rather than as negatively aged")
    void aFutureDateDoesNotProduceNegativeDrift() {
        // A seed file with a typo in a date should not make a figure look better than fresh, nor
        // send a negative multiplier into an inflation adjustment.
        assertThat(StalenessPolicy.ageInMonths(GATHERED, GATHERED.minusMonths(6))).isZero();
        assertThat(StalenessPolicy.assess(GATHERED, GATHERED.minusMonths(6), LEBANON))
                .isEqualTo(Staleness.FRESH);
    }

    @Test
    @DisplayName("the inflation-adjusted figure is offered, and the stored one is left alone")
    void theAdjustmentIsASuggestionRatherThanAWriteBack() {
        Money stored = Money.of("600.00");

        Money suggested =
                StalenessPolicy.inflationAdjusted(stored, GATHERED, GATHERED.plusMonths(12), LEBANON);

        // A year of 17.3% on 600 is 703.80. It is a number to show the user for confirmation; the
        // stored figure is unchanged, because inflating a researched estimate fabricates precision
        // it never had.
        assertThat(suggested).isEqualTo(Money.of("703.80"));
        assertThat(stored).isEqualTo(Money.of("600.00"));
    }
}
