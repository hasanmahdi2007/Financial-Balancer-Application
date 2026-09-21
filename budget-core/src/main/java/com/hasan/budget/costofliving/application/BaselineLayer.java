package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.BaselineQuery;
import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import java.util.Optional;

/**
 * One place a baseline might come from, asked in turn until one answers.
 *
 * <p>The alternative - nested conditionals walking down the confidence tiers - is the shape that
 * makes adding a data source an edit to a method everybody reads nervously. As a list, adding the
 * deferred official source is appending one entry, and the precedence is legible in a single place
 * rather than inferred from the nesting.
 *
 * <p>A layer may only ever answer with its own {@link #tier()}. That is enforced rather than
 * assumed: a source that returned a crowdsourced figure from the official slot would relabel a guess
 * as a government statistic, which is precisely the failure the confidence model exists to prevent.
 */
public interface BaselineLayer {

    /** The confidence a hit from this layer carries. */
    Confidence tier();

    Optional<ResolvedBaseline> lookup(BaselineQuery query);
}
