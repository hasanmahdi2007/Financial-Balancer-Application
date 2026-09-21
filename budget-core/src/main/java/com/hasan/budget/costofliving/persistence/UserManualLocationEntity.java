package com.hasan.budget.costofliving.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code user_manual_location}, for a user whose city is not in the catalogue.
 *
 * <p>There is deliberately no finder by {@code cityLabel} anywhere in this package. The label is
 * text to show back to the person who typed it; the moment anything resolves a metro from it, a
 * misspelling silently selects another city's cost of living and every number downstream is wrong
 * without anything failing.
 */
@Entity
@Table(name = "user_manual_location")
class UserManualLocationEntity {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "country_code", nullable = false)
    private String countryCode;

    @Column(name = "city_label", nullable = false)
    private String cityLabel;

    protected UserManualLocationEntity() {}

    UserManualLocationEntity(String userId, String countryCode, String cityLabel) {
        this.userId = userId;
        this.countryCode = countryCode;
        this.cityLabel = cityLabel;
    }

    String userId() {
        return userId;
    }

    String countryCode() {
        return countryCode;
    }

    String cityLabel() {
        return cityLabel;
    }
}
