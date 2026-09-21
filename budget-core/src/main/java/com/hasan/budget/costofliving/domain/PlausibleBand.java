package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How far from the country estimate a submitted figure may sit before it stops being believable.
 *
 * <p>Bounds are percentages of the country-level estimate, held as integers so the domain carries no
 * loose decimal fields. They are stored <em>per category</em> and beside the estimate they qualify,
 * because spread differs enormously by category: rent ranges from Wichita to San Francisco inside
 * one country, while a single person's grocery bill does not. A single global tolerance would be too
 * loose for groceries and too tight for rent at the same time.
 *
 * <p>That difference is what catches the mistake this validator exists for. Six hundred dollars is
 * an ordinary Beirut rent and twice any believable Beirut grocery bill, so rent typed into the
 * groceries box fails on groceries while passing on rent - which is exactly the discrimination a
 * flat tolerance cannot make.
 */
public record PlausibleBand(int lowPercent, int highPercent) {

    public PlausibleBand {
        if (lowPercent < 0 || highPercent <= lowPercent) {
            throw new IllegalArgumentException(
                    "expected 0 <= lowPercent < highPercent but got " + lowPercent + ".." + highPercent);
        }
    }

    public Money lowerBound(Money estimate) {
        return scale(estimate, lowPercent);
    }

    public Money upperBound(Money estimate) {
        return scale(estimate, highPercent);
    }

    public boolean accepts(Money claim, Money estimate) {
        return claim.compareTo(lowerBound(estimate)) >= 0 && claim.compareTo(upperBound(estimate)) <= 0;
    }

    private static Money scale(Money estimate, int percent) {
        return new Money(estimate
                .amount()
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
    }
}
