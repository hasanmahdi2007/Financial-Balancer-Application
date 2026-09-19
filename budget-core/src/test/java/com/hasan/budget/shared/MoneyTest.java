package com.hasan.budget.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalisesScaleSoEqualityIgnoresTrailingZeros() {
        assertThat(Money.of("1.5")).isEqualTo(Money.of("1.50"));
        assertThat(new Money(new BigDecimal("3"))).isEqualTo(Money.of(3));
    }

    @Test
    void roundsHalfUpToCents() {
        assertThat(Money.of("1.005")).isEqualTo(Money.of("1.01"));
        assertThat(Money.of("1.004")).isEqualTo(Money.of("1.00"));
    }

    @Test
    void spreadOverRoundsUpSoTheTargetIsActuallyReached() {
        Money perMonth = Money.of(1000).spreadOver(3);

        assertThat(perMonth).isEqualTo(Money.of("333.34"));
        assertThat(perMonth.times(3)).isGreaterThanOrEqualTo(Money.of(1000));
    }

    @Test
    void spreadOverASingleMonthReturnsTheWholeAmount() {
        assertThat(Money.of("250.75").spreadOver(1)).isEqualTo(Money.of("250.75"));
    }

    @Test
    void spreadOverRejectsNonPositiveMonths() {
        assertThatThrownBy(() -> Money.of(100).spreadOver(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("months must be >= 1");
    }

    @Test
    void supportsBasicArithmetic() {
        assertThat(Money.of(10).plus(Money.of("2.50"))).isEqualTo(Money.of("12.50"));
        assertThat(Money.of(10).minus(Money.of("12.50"))).isEqualTo(Money.of("-2.50"));
        assertThat(Money.of("1.25").times(4)).isEqualTo(Money.of(5));
    }

    @Test
    void selectsMinimumAndMaximum() {
        assertThat(Money.of(5).min(Money.of(9))).isEqualTo(Money.of(5));
        assertThat(Money.of(5).max(Money.of(9))).isEqualTo(Money.of(9));
        assertThat(Money.of(-5).max(Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    void reportsSign() {
        assertThat(Money.of(1).isPositive()).isTrue();
        assertThat(Money.of(-1).isNegative()).isTrue();
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.ZERO.isPositive()).isFalse();
    }
}
