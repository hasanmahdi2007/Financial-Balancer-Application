package com.hasan.budget.costofliving.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A row of {@code data_source}: where a figure came from, in words a user can be shown. */
@Entity
@Table(name = "data_source")
class DataSourceEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "url")
    private String url;

    @Column(name = "retrieved_at", nullable = false)
    private LocalDate retrievedAt;

    @Column(name = "license_note", nullable = false)
    private String licenseNote;

    protected DataSourceEntity() {}

    String id() {
        return id;
    }

    String sourceName() {
        return name;
    }

    String url() {
        return url;
    }

    LocalDate retrievedAt() {
        return retrievedAt;
    }

    String licenseNote() {
        return licenseNote;
    }
}
