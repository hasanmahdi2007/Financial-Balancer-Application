package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.List;
import java.util.Objects;

/**
 * A question the app asks the user for a number, carrying everything needed to ask it honestly.
 *
 * <p>Exists because "what is your discretionary floor?" is not a question anyone can answer. A person
 * asked for a figure needs to know three things: what the number covers, why it is being asked, and
 * what happens if they skip it. Leaving that to copy in a template means it drifts the moment a
 * category is added, so the explanation is assembled from the same table that drives the behaviour.
 *
 * @param covers the categories this figure spans, each carrying its own user-facing label and
 *     examples. The interface renders these rather than inventing its own wording.
 * @param suggested the default if the user declines. Never zero, and never silently applied - it is
 *     shown as a pre-filled value they can accept or change.
 * @param basis plain-language grounding for the suggestion, such as "typical for someone in Beirut
 *     who goes out regularly". A suggested number with no stated basis reads as arbitrary.
 */
public record SpendingQuestion(
        String question, String why, List<SpendCategory> covers, Money suggested, String basis) {

    public SpendingQuestion {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(why, "why");
        Objects.requireNonNull(suggested, "suggested");
        Objects.requireNonNull(basis, "basis");
        covers = List.copyOf(covers);
        if (covers.isEmpty()) {
            throw new IllegalArgumentException("a spending question must say what it covers");
        }
        if (suggested.isNegative()) {
            throw new IllegalArgumentException("suggested must not be negative but was " + suggested);
        }
    }

    /**
     * The question that protects a user's quality of life from the optimiser.
     *
     * <p>What it covers is derived from the taxonomy rather than listed here, so that adding a
     * category to the protected set updates this question without anyone remembering to.
     *
     * <p>Without an answer the engine treats every dollar of going out and eating as available, and a
     * goal that falls short produces "stop going out entirely" - correct arithmetic and advice nobody
     * follows.
     */
    public static SpendingQuestion monthlyFloor(Money suggested, String basis) {
        return new SpendingQuestion(
                "What is the least you would want to spend each month on enjoying life?",
                "We will never suggest cutting below this, even to reach a goal faster.",
                ProtectedSpending.categories(),
                suggested,
                basis);
    }

    /**
     * One rendered line per category, for example
     * {@code "Going out and fun - nights out, cinema, games, sports, hobbies"}.
     *
     * <p>Built from {@link SpendCategory#label()} and {@link SpendCategory#covers()} so that adding a
     * category updates every explanation automatically.
     */
    public List<String> explainedCoverage() {
        return covers.stream().map(c -> c.label() + " - " + c.covers()).toList();
    }
}
