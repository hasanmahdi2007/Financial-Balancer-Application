package com.hasan.budget.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A rate is the second number in this product that must not be a floating-point fraction. Money
 * covers currency; this covers everything that multiplies it.
 */
class RateTest {

    /**
     * The bug basis points exist to prevent. A decimal fraction cannot hold 20% exactly, so three
     * applications of it stop summing to 60%, and a share of a balance quietly stops reconciling.
     */
    @Test
    void aSharedRateAppliedRepeatedlyStillReconciles() {
        Rate fifth = Rate.ofPercent("20");
        Money hundred = Money.of(100);

        Money thrice = fifth.applyTo(hundred).plus(fifth.applyTo(hundred)).plus(fifth.applyTo(hundred));

        assertThat(thrice).isEqualTo(Rate.ofPercent("60").applyTo(hundred));
    }

    @ParameterizedTest
    @CsvSource({"20, 2000", "17.3, 1730", "0.01, 1", "115, 11500", "100, 10000", "0, 0"})
    void aPercentageBecomesWholeBasisPoints(String percent, int expected) {
        assertThat(Rate.ofPercent(percent).basisPoints()).isEqualTo(expected);
    }

    /**
     * Refused rather than rounded. A rate that silently lost precision on the way in would read
     * back as a different number from the one someone typed into a policy table.
     */
    @Test
    void aRateFinerThanABasisPointIsRefusedRatherThanRounded() {
        assertThatThrownBy(() -> Rate.ofPercent("4.615"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finer than a basis point");
    }

    @Test
    void aNegativeRateIsRejected() {
        assertThatThrownBy(() -> new Rate(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Applying a share lands on the cent, because everything downstream is currency. */
    @Test
    void applyingAShareRoundsToTheCent() {
        assertThat(Rate.ofPercent("4.6").applyTo(Money.of(3_333))).isEqualTo(Money.of("153.32"));
        assertThat(Rate.ofPercent("80").applyTo(Money.of(93_000))).isEqualTo(Money.of(74_400));
    }

    /**
     * Grossing up explains the gap between what someone earns and what lands in their account. It
     * is the only thing an already-taxed user's rate is ever used for.
     */
    @Test
    void grossingUpRecoversTheFigureTaxWasTakenFrom() {
        assertThat(Rate.ofPercent("15").grossUpFrom(Money.of(4_000))).isEqualTo(Money.of("4705.88"));
        assertThat(Rate.ZERO.grossUpFrom(Money.of(4_000))).isEqualTo(Money.of(4_000));
    }

    @Test
    void grossingUpAtOneHundredPercentIsRefused() {
        assertThatThrownBy(() -> Rate.ofPercent("100").grossUpFrom(Money.of(4_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing would be left");
    }

    @Test
    void aRatioIsTheShareOnePartIsOfTheWhole() {
        assertThat(Rate.ratioOf(Money.of(1_800), Money.of(4_000))).isEqualTo(Rate.ofPercent("45"));
        assertThat(Rate.ratioOf(Money.of(3_200), Money.of(4_000))).isEqualTo(Rate.ofPercent("80"));
    }

    /** Nothing is a share of nothing, and answering 0% would put a user in the wrong band. */
    @Test
    void aRatioOfZeroIncomeIsRefusedRatherThanAnsweredWithZero() {
        assertThatThrownBy(() -> Rate.ratioOf(Money.of(100), Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Shown to people, so it reads as a percentage rather than as an internal unit. */
    @Test
    void aRateReadsAsAPercentage() {
        assertThat(Rate.ofPercent("17.3")).hasToString("17.3%");
        assertThat(Rate.ofPercent("20")).hasToString("20%");
    }
}
