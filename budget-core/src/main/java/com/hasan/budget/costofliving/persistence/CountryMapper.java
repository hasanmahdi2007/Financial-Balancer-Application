package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.shared.CountryCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place a stored country row becomes a domain record.
 *
 * <p>It exists because the percentage in the column and the basis points in the domain are two
 * representations of the same number, and two adapters converting it independently is two chances to
 * get the factor wrong in a way no test would notice - a figure would simply age at a hundredth of
 * the right speed.
 */
final class CountryMapper {

    private CountryMapper() {}

    static CountryProfile toProfile(CountryEntity entity) {
        return new CountryProfile(
                new CountryCode(entity.code()),
                entity.countryName(),
                entity.currency(),
                inflationBasisPoints(entity),
                entity.inflationAsOf(),
                entity.listed(),
                entity.dataNote());
    }

    static int inflationBasisPoints(CountryEntity entity) {
        return entity.annualInflationPct()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }
}
