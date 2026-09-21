package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.ContributionState;
import com.hasan.budget.costofliving.domain.Corroboration;
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

/** A row of {@code city_contribution}: a figure offered to everyone, waiting on two gates. */
@Entity
@Table(name = "city_contribution")
class CityContributionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "metro_id")
    private Long metroId;

    @Column(name = "city_label")
    private String cityLabel;

    @Column(name = "country_code", nullable = false)
    private String countryCode;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private SpendCategory category;

    @Column(name = "monthly_amount", nullable = false)
    private BigDecimal monthlyAmount;

    @Column(name = "submitted_at", nullable = false)
    private LocalDate submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_state", nullable = false)
    private ContributionState validationState;

    @Enumerated(EnumType.STRING)
    @Column(name = "corroboration", nullable = false)
    private Corroboration corroboration;

    @Column(name = "validation_notes", nullable = false)
    private String validationNotes;

    protected CityContributionEntity() {}

    CityContributionEntity(
            Long id,
            Long metroId,
            String cityLabel,
            String countryCode,
            String userId,
            SpendCategory category,
            BigDecimal monthlyAmount,
            LocalDate submittedAt,
            ContributionState validationState,
            Corroboration corroboration,
            String validationNotes) {
        this.id = id;
        this.metroId = metroId;
        this.cityLabel = cityLabel;
        this.countryCode = countryCode;
        this.userId = userId;
        this.category = category;
        this.monthlyAmount = monthlyAmount;
        this.submittedAt = submittedAt;
        this.validationState = validationState;
        this.corroboration = corroboration;
        this.validationNotes = validationNotes;
    }

    Long id() {
        return id;
    }

    Long metroId() {
        return metroId;
    }

    String cityLabel() {
        return cityLabel;
    }

    String countryCode() {
        return countryCode;
    }

    String userId() {
        return userId;
    }

    SpendCategory category() {
        return category;
    }

    BigDecimal monthlyAmount() {
        return monthlyAmount;
    }

    LocalDate submittedAt() {
        return submittedAt;
    }

    ContributionState validationState() {
        return validationState;
    }

    Corroboration corroboration() {
        return corroboration;
    }

    String validationNotes() {
        return validationNotes;
    }
}
