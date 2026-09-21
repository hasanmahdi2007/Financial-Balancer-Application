package com.hasan.budget.costofliving.domain;

import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * A user's figure, offered as a city default for everyone else.
 *
 * <p>Distinct from {@link UserOverride}, which is the same number kept private. The separation is
 * the product's promise about bank-derived data: what you type helps you immediately and helps
 * anybody else only after it survives validation and someone approves it.
 *
 * @param metro null when the author's city is not in the catalogue. Their typed {@code cityLabel}
 *     is kept for a human to read while triaging the queue, and is still never a lookup key - it
 *     cannot promote itself into a metro by spelling one almost correctly.
 */
public record Contribution(
        long id,
        String userId,
        CountryCode country,
        MetroId metro,
        String cityLabel,
        SpendCategory category,
        Money amount,
        LocalDate submittedAt,
        ContributionState state,
        Corroboration corroboration,
        String notes) {

    /** Assigned by the store on write; an unsaved contribution carries this instead. */
    public static final long UNSAVED = 0L;

    public Contribution {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(corroboration, "corroboration");
        Objects.requireNonNull(notes, "notes");
        if (metro == null && (cityLabel == null || cityLabel.isBlank())) {
            throw new IllegalArgumentException("a contribution must name a listed city or a typed one");
        }
    }

    public Optional<MetroId> listedCity() {
        return Optional.ofNullable(metro);
    }

    public Contribution judged(ContributionVerdict verdict) {
        return new Contribution(
                id,
                userId,
                country,
                metro,
                cityLabel,
                category,
                amount,
                submittedAt,
                verdict.state(),
                verdict.corroboration(),
                verdict.explanation());
    }

    /**
     * Approval, which is the second of the two gates. A contribution that failed validation cannot
     * be approved past it: the check lives here rather than in the service so that no future caller
     * can reach publication by a different route.
     */
    public Contribution approved() {
        if (state != ContributionState.QUEUED) {
            throw new IllegalStateException(
                    "only a contribution that passed validation can be published, but this one is " + state);
        }
        if (metro == null) {
            throw new IllegalStateException(
                    "this figure was submitted for the typed city name \"" + cityLabel + "\", which is "
                            + "display text and not a place we hold. Publishing it would mean guessing "
                            + "which metro was meant, and a wrong guess is invisible once it is data.");
        }
        return new Contribution(
                id,
                userId,
                country,
                metro,
                cityLabel,
                category,
                amount,
                submittedAt,
                ContributionState.PUBLISHED,
                corroboration,
                notes);
    }
}
