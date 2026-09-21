package com.hasan.budget.costofliving.persistence;

import com.hasan.budget.costofliving.domain.Corroboration;
import com.hasan.budget.shared.SpendCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** A row of {@code user_category_override}: one person's own figure for one category. */
@Entity
@Table(name = "user_category_override")
@IdClass(UserCategoryOverrideEntity.Key.class)
class UserCategoryOverrideEntity {

    /** The natural key. One figure per person per category; saving again replaces it. */
    record Key(String userId, SpendCategory category) implements Serializable {
        Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "user_id")
    private String userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "category")
    private SpendCategory category;

    @Column(name = "monthly_amount", nullable = false)
    private BigDecimal monthlyAmount;

    @Column(name = "set_at", nullable = false)
    private LocalDate setAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "corroboration", nullable = false)
    private Corroboration corroboration;

    protected UserCategoryOverrideEntity() {}

    UserCategoryOverrideEntity(
            String userId,
            SpendCategory category,
            BigDecimal monthlyAmount,
            LocalDate setAt,
            Corroboration corroboration) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.category = Objects.requireNonNull(category, "category");
        this.monthlyAmount = monthlyAmount;
        this.setAt = setAt;
        this.corroboration = corroboration;
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

    LocalDate setAt() {
        return setAt;
    }

    Corroboration corroboration() {
        return corroboration;
    }
}
