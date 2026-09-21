package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.BaselineQuery;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.UserOverride;
import com.hasan.budget.costofliving.port.UserOverrideStore;
import java.util.Objects;
import java.util.Optional;

/**
 * The first layer: what the user told us about themselves.
 *
 * <p>It outranks official government statistics, permanently and without expiry. That is not
 * deference for its own sake - the user is the only source with access to their actual receipts, and
 * an app that overrules them on what their own rent is has stopped being useful to them.
 */
public final class UserOverrideLayer implements BaselineLayer {

    private final UserOverrideStore overrides;

    public UserOverrideLayer(UserOverrideStore overrides) {
        this.overrides = Objects.requireNonNull(overrides, "overrides");
    }

    @Override
    public Confidence tier() {
        return Confidence.USER_PROVIDED;
    }

    @Override
    public Optional<ResolvedBaseline> lookup(BaselineQuery query) {
        return overrides.find(query.userId(), query.category()).map(UserOverride::asBaseline);
    }
}
