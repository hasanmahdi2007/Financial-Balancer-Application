package com.hasan.budget.costofliving.port;

import com.hasan.budget.costofliving.domain.PlausibleBand;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.Map;
import java.util.Optional;

/**
 * The country-level figures, which do two jobs and are therefore one source rather than two.
 *
 * <p>They are the last layer of the resolver, so that a city missing a category lands on an estimate
 * labelled as such instead of on zero. They are also the pre-fill for the manual form, which is what
 * stops a user whose city is not listed from facing eleven blank boxes.
 *
 * <p>The plausible band lives here too, beside the figure it qualifies, because it is meaningless
 * without it: a band is a multiple of an estimate, and separating them would let one be updated
 * without the other.
 */
public interface CountryBaselineSource {

    Optional<ResolvedBaseline> countryBaseline(CountryCode country, SpendCategory category);

    /** Every category this country has an estimate for, for pre-filling a form in one call. */
    Map<SpendCategory, ResolvedBaseline> countryBaselines(CountryCode country);

    /**
     * How far a user-submitted figure may sit from the estimate before it stops being believable.
     * Empty where no estimate exists, in which case there is nothing to judge a claim against and
     * the validator must say so rather than inventing a bound.
     */
    Optional<PlausibleBand> plausibleBand(CountryCode country, SpendCategory category);

    /** Convenience for the common pairing: the band and the estimate it is a multiple of. */
    default Optional<Money> estimateAmount(CountryCode country, SpendCategory category) {
        return countryBaseline(country, category).map(ResolvedBaseline::amount);
    }
}
