package com.hasan.budget.planning.application;

import com.hasan.budget.planning.domain.DiscretionarySpend;
import com.hasan.budget.planning.domain.Tradeoff;
import com.hasan.budget.planning.domain.surplus.CategoryLine;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What the allocator may still propose cutting, once the surplus has already assumed its reductions.
 *
 * <p><strong>This class exists because joining the surplus to the allocator naively produces a plan
 * that lies.</strong> The surplus is not money in hand: it is what the user would have <em>if</em>
 * they brought controllable spending down to the local figure and fun down to the least they want
 * protected. Handing the allocator each category's <em>observed</em> spend as cut candidates offers
 * up those same dollars a second time - it proposes a cut, reports no gap, and a user who follows the
 * advice exactly lands short by the reduction the surplus had already counted on.
 *
 * <p>So a candidate is <em>headroom</em>: what is still available after that assumption, and nothing
 * the user or the product has promised to leave alone.
 *
 * <ul>
 *   <li><b>Capped at the local figure</b> (groceries, utilities, transport): none. The surplus charged
 *       only {@code min(spent, local figure)}, so everything above it is already assumed away and
 *       going lower would mean proposing to live below what things cost here - a different and much
 *       louder thing to say than a cut.
 *   <li><b>Fun money</b> (eating out, going out, clothes): none. The surplus assumed this comes down
 *       to the least the user wants to spend on enjoying life, and that figure is a promise - the
 *       question that sets it says "we will never suggest cutting below this, even to reach a goal
 *       faster". Anything above it is already in the assumed reduction.
 *   <li><b>Taken as-is</b> (subscriptions and the like): the full amount, because the surplus charged
 *       the full amount and assumed nothing away. A thing the user named inside the category is
 *       offered on its own, with the user's own answer about how willing they are, and is taken out
 *       of the category's figure so the same dollars are not offered twice.
 * </ul>
 *
 * <p><strong>The brief words that third case the other way round</strong> - "the headroom is what
 * observed spending exceeds the floor" - and that reading is the one thing this class exists to
 * prevent, one layer further in. Those dollars are precisely what the surplus already assumed away,
 * so offering them again as a cut counts them twice: the plan would then ask for a bigger reduction
 * than the largest one the user could make with fun money still standing at the floor, and the
 * remainder could only be found below it. P7 says the same from its own side - {@code
 * ResolutionOption} records that a plan which will not balance has exactly two honest answers, and
 * that the engine must never instead balance the arithmetic quietly by cutting below a floor. So the
 * arm stays at zero, and PlanAssemblerTest measures that difference rather than asserting it.
 *
 * <p>Each line's own {@link CategoryLine#rigidity()} decides, never the category default, which is what
 * lets a gym the user marked as unchangeable reach the allocator unchangeable. The allocator reports
 * whatever it could not cover as a residual gap, and that is the honest answer: the rest can only be
 * found by counting more of the balance or by giving a goal more time.
 */
public final class CutCandidates {

    private final List<Group> groups;

    private CutCandidates(List<Group> groups) {
        this.groups = List.copyOf(groups);
    }

    /**
     * @param lines the breakdown's lines, which carry each line's own rigidity
     * @param lineItems the named items behind those lines, needed only to know which of them sit inside
     *     a category's figure rather than on top of it
     */
    public static CutCandidates from(List<CategoryLine> lines, List<UserLineItem> lineItems) {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(lineItems, "lineItems");

        Map<SpendCategory, Money> namedWithin = NamedItems.withinCategories(lineItems);

        Map<Key, Group> grouped = new LinkedHashMap<>();
        for (CategoryLine line : lines) {
            Money headroom = headroomOf(line, namedWithin);
            if (line.isCuttable() && headroom.isPositive()) {
                grouped.computeIfAbsent(new Key(line.category(), line.rigidity()), Group::new).add(line, headroom);
            }
        }
        return new CutCandidates(new ArrayList<>(grouped.values()));
    }

    private static Money headroomOf(CategoryLine line, Map<SpendCategory, Money> namedWithin) {
        // One switch, over the policy - never over the category - for the same reason the surplus
        // calculation has exactly one.
        return switch (line.policy()) {
            case CAP_AT_BASELINE, DISCRETIONARY -> Money.ZERO;
            case TAKE_AS_IS -> line.lineItemId() != null
                    ? line.actual()
                    : line.actual().minus(namedWithin.getOrDefault(line.category(), Money.ZERO)).max(Money.ZERO);
        };
    }

    /** What the allocator receives: one candidate per category and willingness. */
    public List<DiscretionarySpend> forAllocator() {
        return groups.stream()
                .map(group -> new DiscretionarySpend(group.key.category, group.total, group.key.rigidity))
                .toList();
    }

    public Money total() {
        return groups.stream().map(group -> group.total).reduce(Money.ZERO, Money::plus);
    }

    /**
     * The allocator's cuts, named again as the user named the lines behind them.
     *
     * <p>A {@link Tradeoff} carries only a category, so two candidates in the same category - the
     * subscriptions figure and a gym named inside it, with different answers about how willing the
     * user is - come back indistinguishable. They are told apart by the one ordering the allocator
     * states as policy: it cuts in rigidity order, so within one category the n-th cut belongs to the
     * n-th candidate by rigidity.
     */
    public List<SuggestedCut> name(List<Tradeoff> tradeoffs) {
        Map<SpendCategory, Deque<Group>> byCategory = new EnumMap<>(SpendCategory.class);
        groups.stream()
                .sorted(Comparator.comparing((Group group) -> group.key.rigidity))
                .forEach(group -> byCategory
                        .computeIfAbsent(group.key.category, category -> new ArrayDeque<>())
                        .add(group));

        List<SuggestedCut> named = new ArrayList<>(tradeoffs.size());
        for (Tradeoff tradeoff : tradeoffs) {
            Deque<Group> candidates = byCategory.get(tradeoff.category());
            if (candidates == null || candidates.isEmpty()) {
                throw new IllegalStateException(
                        "the allocator proposed cutting " + tradeoff.category() + ", which was never offered to it");
            }
            Group group = candidates.poll();
            named.add(new SuggestedCut(
                    group.label(), tradeoff.category(), tradeoff.suggestedReduction(), group.key.rigidity, group.lineIds));
        }
        return named;
    }

    /**
     * One cut, named.
     *
     * @param label the user's own names for what is being cut, e.g. "Gym membership", or the
     *     category's label when the cut is against the category as a whole
     * @param lineIds the plan lines this cut draws on
     */
    public record SuggestedCut(
            String label, SpendCategory category, Money amount, Rigidity rigidity, List<String> lineIds) {

        public SuggestedCut {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(rigidity, "rigidity");
            lineIds = List.copyOf(lineIds);
        }
    }

    private record Key(SpendCategory category, Rigidity rigidity) {}

    private static final class Group {
        private final Key key;
        private final List<String> labels = new ArrayList<>();
        private final List<String> lineIds = new ArrayList<>();
        private Money total = Money.ZERO;

        private Group(Key key) {
            this.key = key;
        }

        private void add(CategoryLine line, Money headroom) {
            labels.add(line.label());
            lineIds.add(line.lineItemId() != null ? line.lineItemId() : Keys.of(line.category()));
            total = total.plus(headroom);
        }

        private String label() {
            return labels.stream().distinct().collect(Collectors.joining(" and "));
        }
    }
}
