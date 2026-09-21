package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.BankEvidence;
import com.hasan.budget.costofliving.domain.Contribution;
import com.hasan.budget.costofliving.domain.ContributionState;
import com.hasan.budget.costofliving.domain.ContributionVerdict;
import com.hasan.budget.costofliving.domain.Corroboration;
import com.hasan.budget.costofliving.port.ContributionStore;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The path a figure takes from one person's account to everybody's defaults.
 *
 * <p>Two things happen when a user shares a figure, and only one of them is visible to anyone else.
 * It becomes their own override immediately - it is their money and their plan. A copy enters the
 * queue as a candidate, and it stays invisible to every other user until it has passed the
 * validators <em>and</em> been approved. Neither gate alone publishes anything.
 */
public final class ContributionService {

    private final ContributionStore store;
    private final ContributionValidator validator;
    private final UserFigureService userFigures;
    private final Clock clock;

    public ContributionService(
            ContributionStore store,
            ContributionValidator validator,
            UserFigureService userFigures,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.userFigures = Objects.requireNonNull(userFigures, "userFigures");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Saves the figure for its author and queues a copy for review.
     *
     * @param metro null when the author's city is not in the catalogue; their typed label is then
     *     carried for a human to read, and still never used to look anything up.
     */
    public Contribution submit(
            String userId,
            CountryCode country,
            MetroId metro,
            String cityLabel,
            SpendCategory category,
            Money amount,
            Optional<BankEvidence> evidence) {
        userFigures.record(userId, category, amount, evidence);
        Contribution candidate = new Contribution(
                Contribution.UNSAVED,
                userId,
                country,
                metro,
                cityLabel,
                category,
                amount,
                LocalDate.now(clock),
                ContributionState.CANDIDATE,
                Corroboration.NOT_CHECKED,
                "");
        ContributionVerdict verdict = validator.validate(candidate, evidence);
        return store.save(candidate.judged(verdict));
    }

    public List<Contribution> awaitingApproval() {
        return store.queued();
    }

    /**
     * The second gate. Publishing a contribution that never passed validation throws rather than
     * being quietly permitted, because an approval screen is exactly where a tired person clicks
     * through a figure nobody checked.
     */
    public Contribution approve(long contributionId) {
        Contribution contribution = store.find(contributionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no contribution with id " + contributionId));
        return store.publish(contribution.approved());
    }
}
