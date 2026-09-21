package com.hasan.budget.costofliving.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A row of {@code country}.
 *
 * <p>Entities in this package never leave it. Everything crossing the boundary is a domain record,
 * which is what lets the JPA adapter be swapped for the classpath one without a single caller
 * noticing.
 */
@Entity
@Table(name = "country")
class CountryEntity {

    @Id
    @Column(name = "code")
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "annual_inflation_pct", nullable = false)
    private BigDecimal annualInflationPct;

    @Column(name = "inflation_as_of", nullable = false)
    private LocalDate inflationAsOf;

    @Column(name = "data_note", nullable = false)
    private String dataNote;

    @Column(name = "listed", nullable = false)
    private boolean listed;

    protected CountryEntity() {}

    String code() {
        return code;
    }

    String countryName() {
        return name;
    }

    String currency() {
        return currency;
    }

    BigDecimal annualInflationPct() {
        return annualInflationPct;
    }

    LocalDate inflationAsOf() {
        return inflationAsOf;
    }

    String dataNote() {
        return dataNote;
    }

    boolean listed() {
        return listed;
    }
}
