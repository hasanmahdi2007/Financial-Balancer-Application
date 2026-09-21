package com.hasan.budget.costofliving.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code metro_area}.
 *
 * <p>The surrogate id is a database concern and stops here. Outside this package a city is a
 * {@code MetroId} over the slug, so nothing downstream can come to depend on an integer that would
 * change the next time the seed data is rebuilt.
 */
@Entity
@Table(name = "metro_area")
class MetroAreaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "country_code", nullable = false)
    private String countryCode;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "admin1")
    private String admin1;

    @Column(name = "external_geo_id")
    private String externalGeoId;

    @Column(name = "population")
    private Integer population;

    @Column(name = "sort_rank", nullable = false)
    private int sortRank;

    protected MetroAreaEntity() {}

    Long id() {
        return id;
    }

    String countryCode() {
        return countryCode;
    }

    String slug() {
        return slug;
    }

    String displayName() {
        return displayName;
    }

    String admin1() {
        return admin1;
    }

    Integer population() {
        return population;
    }

    int sortRank() {
        return sortRank;
    }
}
