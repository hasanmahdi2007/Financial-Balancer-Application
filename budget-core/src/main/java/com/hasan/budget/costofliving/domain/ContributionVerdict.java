package com.hasan.budget.costofliving.domain;

import java.util.List;
import java.util.Objects;

/**
 * What the validators concluded, and why, in words a person could be shown.
 *
 * <p>The reasons are carried rather than logged because a rejected submission with no explanation
 * reads as the app calling the user a liar. "That is more than twice a typical Beirut grocery bill -
 * did it belong in the rent box?" is a question they can act on.
 */
public record ContributionVerdict(
        ContributionState state, Corroboration corroboration, List<String> reasons) {

    public ContributionVerdict {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(corroboration, "corroboration");
        reasons = List.copyOf(reasons);
    }

    /** One line, already joined, for an interface that has room for a sentence and not a list. */
    public String explanation() {
        return String.join(" ", reasons);
    }
}
