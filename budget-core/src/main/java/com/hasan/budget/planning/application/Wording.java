package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.domain.Staleness;
import com.hasan.budget.planning.domain.AllocationStatus;
import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.planning.domain.decision.ResolutionOption;
import com.hasan.budget.shared.Rigidity;
import java.util.EnumMap;
import java.util.Map;

/**
 * The words for the four enums this module shows a person that do not yet carry their own.
 *
 * <p>Most of the taxonomy carries {@code label()} beside its behaviour - {@code SpendCategory},
 * {@code Confidence}, {@code LifestyleTier}, every decision enum - and those are used directly.
 * {@link Rigidity}, {@link Priority}, {@link AllocationStatus} and {@link Staleness} predate that
 * convention and live in packages this module does not own, so their wording is kept here, in one
 * table per enum, and each table is checked complete at class load: adding a constant without words
 * fails immediately rather than shipping a blank label. Moving these onto the enums themselves is
 * the better home and a mechanical change when their owners want it.
 */
public final class Wording {

    private Wording() {}

    /** A label and the sentence behind it. */
    public record Words(String label, String meaning) {}

    private static final Map<Rigidity, Words> HOW_WILLING = complete(Rigidity.class, Map.of(
            Rigidity.DISPOSABLE, new Words(
                    "Not very important",
                    "We suggest cutting this first, and it can go all the way to zero."),
            Rigidity.FLEXIBLE, new Words(
                    "Flexible",
                    "A normal place to find money, but not below what it typically costs where you live."),
            Rigidity.ESSENTIAL, new Words(
                    "Very important",
                    "Only touched as a last resort, and never below what it typically costs where you live."),
            Rigidity.LOCKED, new Words(
                    "Cannot be changed",
                    "We will never suggest cutting it. At most we mention a way to make the thing itself cheaper.")));

    private static final Map<Priority, Words> PRIORITY = complete(Priority.class, Map.of(
            Priority.CRITICAL, new Words("Must happen", "Funded before every other goal."),
            Priority.HIGH, new Words("Very important", "Funded before anything you marked as less important."),
            Priority.MEDIUM, new Words("Important", "Funded once the goals you marked as more important are on track."),
            Priority.LOW, new Words("Nice to have", "Funded from whatever is left once everything else is on track.")));

    private static final Map<AllocationStatus, Words> STATUS = complete(AllocationStatus.class, Map.of(
            AllocationStatus.COMPLETED, new Words(
                    "Already covered",
                    "What you already have is enough for this goal, so it needs nothing more each month."),
            AllocationStatus.ON_TRACK, new Words(
                    "On track",
                    "It gets everything it needs each month to arrive by its date."),
            AllocationStatus.AT_RISK, new Words(
                    "Behind",
                    "It gets part of what it needs each month, so it will be late unless something changes."),
            AllocationStatus.INFEASIBLE, new Words(
                    "Not funded yet",
                    "Nothing is left for it each month once the goals ahead of it are paid for.")));

    private static final Map<Staleness, Words> AGEING = complete(Staleness.class, Map.of(
            Staleness.FRESH, new Words("Current", "Prices have barely moved since this was gathered."),
            Staleness.AGING, new Words(
                    "Gathered a while ago",
                    "Prices have moved a little since this was gathered. It is still a fair guide."),
            Staleness.STALE, new Words(
                    "Probably out of date",
                    "Prices have moved a lot since this was gathered. Your own figure would make the plan more accurate.")));

    /**
     * The request that acts on each way out of a plan that does not balance. Counting more of the
     * balance is not a new mechanism: it is the same earmarking lever the plan already uses, reached by
     * changing the money in scope.
     */
    private static final Map<ResolutionOption, String> ANSWER_WITH = complete(ResolutionOption.class, Map.of(
            ResolutionOption.COUNT_MORE_OF_YOUR_BALANCE, "PUT /api/v1/money",
            ResolutionOption.GIVE_A_GOAL_MORE_TIME, "PUT /api/v1/goals/{id}"));

    public static Words howWilling(Rigidity rigidity) {
        return HOW_WILLING.get(rigidity);
    }

    public static Words priority(Priority priority) {
        return PRIORITY.get(priority);
    }

    public static Words status(AllocationStatus status) {
        return STATUS.get(status);
    }

    public static Words ageing(Staleness staleness) {
        return AGEING.get(staleness);
    }

    public static String answerWith(ResolutionOption option) {
        return ANSWER_WITH.get(option);
    }

    private static <E extends Enum<E>, V> Map<E, V> complete(Class<E> type, Map<E, V> table) {
        Map<E, V> byConstant = new EnumMap<>(type);
        byConstant.putAll(table);
        for (E constant : type.getEnumConstants()) {
            if (!byConstant.containsKey(constant)) {
                throw new IllegalStateException(
                        constant + " has no wording, so it would reach a person as a bare constant");
            }
        }
        return byConstant;
    }
}
