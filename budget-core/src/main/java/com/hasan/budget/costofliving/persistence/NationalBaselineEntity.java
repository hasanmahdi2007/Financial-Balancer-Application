package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.SpendCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A row of {@code national_baseline}: the country fallback, and the band that qualifies it. */
@Entity
@Table(name = "national_baseline")
class NationalBaselineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "country_code", nullable = false)
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private SpendCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "income_quintile")
    private IncomeQuintile incomeQuintile;

    @Column(name = "monthly_amount", nullable = false)
    private BigDecimal monthlyAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false)
    private Confidence confidence;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Column(name = "low_pct", nullable = false)
    private int lowPct;

    @Column(name = "high_pct", nullable = false)
    private int highPct;

    protected NationalBaselineEntity() {}

    String countryCode() {
        return countryCode;
    }

    SpendCategory category() {
        return category;
    }

    IncomeQuintile incomeQuintile() {
        return incomeQuintile;
    }

    BigDecimal monthlyAmount() {
        return monthlyAmount;
    }

    Confidence confidence() {
        return confidence;
    }

    String sourceId() {
        return sourceId;
    }

    LocalDate asOf() {
        return asOf;
    }

    int lowPct() {
        return lowPct;
    }

    int highPct() {
        return highPct;
    }
}
