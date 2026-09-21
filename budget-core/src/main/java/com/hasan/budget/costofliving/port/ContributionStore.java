package com.hasan.budget.costofliving.port;

import com.hasan.budget.costofliving.domain.Contribution;
import java.util.List;
import java.util.Optional;

/**
 * Where submitted city figures wait, and how one of them becomes a default for everybody.
 */
public interface ContributionStore {

    /** Assigns an id on first write and returns the stored form. */
    Contribution save(Contribution contribution);

    Optional<Contribution> find(long id);

    /** Everything that passed validation and is waiting on approval. */
    List<Contribution> queued();

    /**
     * Records the approval and writes the figure as a {@code CONTRIBUTED} city default.
     *
     * <p>Both writes or neither. A published contribution whose city default never appeared would
     * look approved to its author and be invisible to everyone else, which is worse than a failure -
     * nothing surfaces it, so nobody goes looking.
     */
    Contribution publish(Contribution approved);
}
