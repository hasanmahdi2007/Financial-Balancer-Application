package com.hasan.budget.costofliving.domain;

/**
 * How far a shared figure has travelled from "one person typed it" to "everyone sees it".
 *
 * <p>Two gates, never one. A submission must pass the validators <em>and</em> be approved; neither
 * alone publishes anything. That is deliberate: the validators are statistics and statistics can be
 * gamed, while approval alone would make the owner a bottleneck who rubber-stamps figures nobody
 * checked.
 */
public enum ContributionState {
    /** Written, not yet judged. */
    CANDIDATE,
    /** A validator said no. It remains the author's own figure; it simply never becomes a default. */
    REJECTED,
    /** Passed every validator, waiting on approval. */
    QUEUED,
    /** Approved. It is now a CONTRIBUTED city default for everyone. */
    PUBLISHED
}
