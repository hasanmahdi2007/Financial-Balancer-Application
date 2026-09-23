package com.hasan.budget.planning.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A plan exactly as a person reads it, and exactly as it is stored.
 *
 * <p>The same shape is the response body and the snapshot, on purpose. A snapshot is a record of what
 * the user was told; storing the inputs and recomputing on read would show them today's arithmetic
 * over yesterday's inputs, which is not what the plan said. Append-only storage of this shape is what
 * lets history show exactly what changed when a goal was added.
 *
 * <p>Every amount is a string with two decimals, so the client neither parses a float nor loses a
 * trailing zero. Every enum arrives as words - a {@link KeyLabel} where the client may send the value
 * back, a {@link LabelMeaning} where it only shows it - and never as a constant name.
 */
public record PlanView(
        String id,
        Instant takenAt,
        LocalDate asOf,
        String reason,
        MoneyInScope money,
        Surplus surplus,
        List<Goal> goals,
        Cuts cuts,
        List<Hint> hints,
        LeftOver leftOver) {

    public PlanView {
        goals = List.copyOf(goals);
        hints = List.copyOf(hints);
    }

    /** A value the client may send back, with the words to show for it. */
    public record KeyLabel(String key, String label) {}

    /** A value the client only shows, with the sentence that explains it. */
    public record LabelMeaning(String label, String meaning) {}

    /**
     * @param putTowardGoals the part of the balance credited to goals, which is why they need less
     * @param leftUnassigned the part no goal needed. Only this counts towards the runway.
     */
    public record MoneyInScope(
            String monthlyIncome,
            String balanceInScope,
            String setAside,
            String putTowardGoals,
            String leftUnassigned,
            Runway runway) {}

    /** @param months null when the money is not running down at all */
    public record Runway(String label, Integer months) {}

    /**
     * @param assumedReduction what the monthly figure already takes for granted. Never optional: the
     *     monthly figure is not money in hand, and without this beside it the plan reads as a promise.
     */
    public record Surplus(
            String amount,
            String explanation,
            String assumedReduction,
            String assumedReductionExplanation,
            List<Reduction> reductions,
            List<Line> lines,
            String leastForEnjoyingLife,
            String leastForEnjoyingLifeBasis,
            String alreadySaving,
            String alreadySavingExplanation) {

        public Surplus {
            reductions = List.copyOf(reductions);
            lines = List.copyOf(lines);
        }
    }

    public record Reduction(String label, String from, String to, String by) {}

    /**
     * @param localFigure what this typically costs where the user lives, or null where nothing is
     *     measured against one
     * @param counted what the monthly figure actually charged for this line
     * @param assumed true when neither the user nor their bank said what they spend here and the
     *     local figure was used
     * @param measuredFrom the month this figure was read off the user's own account, worded for them,
     *     or null when it did not come from a bank. Named rather than flagged because a figure the user
     *     can check against a statement is the only kind they have any reason to believe.
     * @param basis where the local figure came from, or null when there is none
     */
    public record Line(
            String id,
            String label,
            KeyLabel category,
            String spent,
            String localFigure,
            String counted,
            boolean assumed,
            String measuredFrom,
            KeyLabel howWilling,
            Basis basis) {}

    /** @param ageing null while the figure is still current, so only a figure worth questioning is flagged */
    public record Basis(String label, String explanation, LocalDate gathered, LabelMeaning ageing) {}

    /**
     * @param fromBalance the part of the balance credited to this goal. There is no other way for a
     *     goal to hold money, which is what stops the same dollars counting twice.
     */
    public record Goal(
            String id,
            String name,
            KeyLabel priority,
            String target,
            String fromBalance,
            String stillNeeded,
            LocalDate deadline,
            String monthlyNeeded,
            String monthlyFunded,
            String shortBy,
            boolean finishFirst,
            LabelMeaning status) {}

    /**
     * Both numbers, together, always.
     *
     * @param suggested cuts on top of what the monthly figure already assumed
     * @param alreadyAssumed the same figure as {@code surplus.assumedReduction}, repeated here so the
     *     cuts are never read without it
     * @param totalChange everything the user must change for the plan to hold
     * @param stillShort what no cut the plan is allowed to suggest can find. Above zero, the two real
     *     ways out arrive in {@code options}.
     */
    public record Cuts(
            List<Cut> suggested,
            String suggestedTotal,
            String alreadyAssumed,
            String totalChange,
            String totalChangeExplanation,
            String stillShort,
            List<Option> options) {

        public Cuts {
            suggested = List.copyOf(suggested);
            options = List.copyOf(options);
        }
    }

    public record Cut(String label, KeyLabel category, String by, KeyLabel howWilling, List<String> lineIds) {

        public Cut {
            lineIds = List.copyOf(lineIds);
        }
    }

    /** @param answerWith the request that acts on this option */
    public record Option(String label, String meaning, String answerWith) {}

    /** A sentence, never a number. It must not be rendered inside a total. */
    public record Hint(String id, String label, String hint) {}

    public record LeftOver(String amount, String explanation) {}
}
