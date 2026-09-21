package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.BaselineQuery;
import com.hasan.budget.costofliving.domain.CityBaselines;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which number wins, by asking each layer in order until one answers.
 *
 * <p>The whole of the precedence rule is the list handed to the constructor:
 *
 * <pre>
 *   the user's own figure  -&gt;  official  -&gt;  contributed  -&gt;  crowdsourced  -&gt;  country estimate
 * </pre>
 *
 * <p>Nothing here knows what those layers read. That is what keeps the promise the architecture is
 * built around: the cost-of-living data source can be replaced without touching the allocator, or
 * this class, or any caller.
 */
public final class BaselineResolver {

    private final List<BaselineLayer> layers;

    public BaselineResolver(List<BaselineLayer> layers) {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("a resolver with no layers can only ever answer nothing");
        }
        this.layers = List.copyOf(layers);
    }

    /** The layers in the order they are consulted, which some diagnostics and tests want to see. */
    public List<BaselineLayer> layers() {
        return layers;
    }

    public Optional<ResolvedBaseline> resolve(BaselineQuery query) {
        for (BaselineLayer layer : layers) {
            Optional<ResolvedBaseline> hit = layer.lookup(query);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** Every category resolved for one person, each independently through the whole chain. */
    public Map<SpendCategory, ResolvedBaseline> resolveAll(BaselineQuery query) {
        Map<SpendCategory, ResolvedBaseline> resolved = new EnumMap<>(SpendCategory.class);
        for (SpendCategory category : SpendCategory.values()) {
            resolve(query.forCategory(category)).ifPresent(baseline -> resolved.put(category, baseline));
        }
        return Map.copyOf(resolved);
    }

    /**
     * The whole city as one set, dated by its weakest figure.
     *
     * <p>The oldest date wins because a plan is only as current as the worst number in it, and
     * reporting the freshest would let one recently corrected figure vouch for nine stale ones.
     */
    public CityBaselines cityBaselines(BaselineQuery query) {
        MetroId metro = query.cityIfListed()
                .orElseThrow(() -> new IllegalArgumentException(
                        "this user has no listed city, so ask for their categories rather than a city set"));
        Map<SpendCategory, ResolvedBaseline> resolved = resolveAll(query);
        LocalDate oldest = resolved.values().stream()
                .map(ResolvedBaseline::asOf)
                .min(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalStateException(
                        "no figure of any kind resolved for " + metro.slug()));
        return new CityBaselines(metro, resolved, oldest);
    }
}
