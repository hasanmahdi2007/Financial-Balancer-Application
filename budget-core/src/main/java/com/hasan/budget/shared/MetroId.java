package com.hasan.budget.shared;

import java.util.Objects;

/**
 * Identifies a city we hold data for.
 *
 * <p>A typed value rather than a bare string so that a user-entered city label, which is display-only
 * and never a lookup key, cannot be passed where a real metro is expected. That distinction is what
 * removes geocoding and fuzzy matching from the product entirely.
 */
public record MetroId(String slug) {

    public MetroId {
        Objects.requireNonNull(slug, "slug");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
    }
}
