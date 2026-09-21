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

/**
 * A row of {@code city_category_baseline}.
 *
 * <p>Every enum is stored by name. An ordinal column plus somebody later reordering an enum is
 * silent data corruption: existing rows change meaning with no error anywhere, and a plan built on
 * them is confidently wrong. An architecture rule enforces it, because the trap is the bare
 * {@code @Enumerated} that quietly defaults to ordinal rather than the explicit choice.
 */
@Entity
@Table(name = "city_category_baseline")
class CityCategoryBaselineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "metro_id", nullable = false)
    private Long metroId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private SpendCategory category;

    /** Null on every curated row. Nullable here is what keeps the deferred pipeline a new class. */
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

    @Column(name = "derivation")
    private String derivation;

    protected CityCategoryBaselineEntity() {}

    CityCategoryBaselineEntity(
            Long metroId,
            SpendCategory category,
            IncomeQuintile incomeQuintile,
            BigDecimal monthlyAmount,
            Confidence confidence,
            String sourceId,
            LocalDate asOf,
            String derivation) {
        this.metroId = metroId;
        this.category = category;
        this.incomeQuintile = incomeQuintile;
        this.monthlyAmount = monthlyAmount;
        this.confidence = confidence;
        this.sourceId = sourceId;
        this.asOf = asOf;
        this.derivation = derivation;
    }

    Long metroId() {
        return metroId;
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
}
