package com.hasan.budget.planning.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * There is one stored number and the percentage is a view of it. These cases pin down the edges of
 * that claim, since the whole point of holding one field is that the two views cannot disagree.
 */
class ShareTest {

    private static final Money POOL = Money.of(7000);

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 10, 25, 50, 99, 100})
    void everyPercentageReadsBackAsItself(int percent) {
        assertThat(Share.ofPercent(percent, POOL).asPercentOf(POOL)).isEqualTo(percent);
    }

    /**
     * The dollars view is the one that is stored, so it is the one that never loses anything. The
     * percentage is rounded for display and may be a whole percent off what the user typed, which is
     * fine precisely because no arithmetic reads it.
     */
    @Test
    void theStoredAmountIsExactEvenWhereThePercentageIsNot() {
        Share awkward = Share.ofAmount(Money.of("123.45"));

        assertThat(awkward.amount()).isEqualTo(Money.of("123.45"));
        assertThat(awkward.asPercentOf(POOL)).isEqualTo(2);
        // Reading it back as a percentage and forward again is lossy, which is exactly why the
        // percentage is never the stored value.
        assertThat(Share.ofPercent(awkward.asPercentOf(POOL), POOL).amount()).isEqualTo(Money.of(140));
    }

    /** No pool to be a share of means no percentage, rather than a division by zero. */
    @Test
    void aShareOfNothingIsZeroPercentRatherThanAnError() {
        assertThat(Share.ofAmount(Money.of(50)).asPercentOf(Money.ZERO)).isZero();
        assertThat(Share.ofPercent(10, Money.ZERO).amount()).isEqualTo(Money.ZERO);
    }

    @Test
    void aNegativeShareIsRejected() {
        assertThatThrownBy(() -> Share.ofAmount(Money.of(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> Share.ofPercent(-5, POOL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("percent");
    }
}
