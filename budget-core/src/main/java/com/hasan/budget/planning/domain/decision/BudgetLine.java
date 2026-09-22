package com.hasan.budget.planning.domain.decision;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.util.Objects;

/**
 * One line of the month's plan as it stands, and how far it may move.
 *
 * <p>Named, not merely categorised, for the same reason the surplus breakdown carries line items:
 * "cut your gym by $20" is advice somebody can act on and "cut Subscriptions by $20" is not.
 *
 * @param floor the least this line can be without the plan becoming fiction, which is its localised
 *     baseline. Passed in already resolved, because the planning domain never sees a city or a data
 *     source - the same seam that keeps the allocator free of them.
 * @param rigidity the user's own answer about this line, defaulted from its category. It is the axis
 *     rebalancing obeys absolutely.
 */
public record BudgetLine(
        String id, String label, SpendCategory category, Money amount, Money floor, Rigidity rigidity) {

    public BudgetLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(rigidity, "rigidity");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("amount must not be negative but was " + amount);
        }
        if (floor.isNegative()) {
            throw new IllegalArgumentException("floor must not be negative but was " + floor);
        }
    }

    /** Uses the category's default rigidity, for callers with no answer from the user to apply. */
    public static BudgetLine of(String id, String label, SpendCategory category, Money amount, Money floor) {
        Objects.requireNonNull(category, "category");
        return new BudgetLine(id, label, category, amount, floor, category.defaultRigidity());
    }

    /**
     * Whether rebalancing may take anything from this line at all.
     *
     * <p>Two conditions, mirroring the ones the allocator applies, so a line the engine has promised
     * never to touch cannot be raided here through a different door. A locked line is the user's own
     * "cannot be changed" and is never cut. A category the engine refuses to name numerically -
     * "Everything else" - is not cut either, because an instruction to spend $150 less on spending we
     * could not identify is not something anyone can follow.
     */
    public boolean mayGive() {
        return rigidity != Rigidity.LOCKED && category.autoSuggestCuts();
    }

    /** Whether rebalancing may raise this line. A locked line is never raised either. */
    public boolean mayRise() {
        return rigidity != Rigidity.LOCKED;
    }

    /**
     * The lowest this line may go while being cut.
     *
     * <p>It is the line's floor for everything except what the user called unimportant, which may go
     * to zero. That is not the floor being ignored: a floor is a local baseline, an estimate of what
     * this costs people here, and the user saying "not very important" is a better answer about their
     * own spending than any baseline. The tiers that do respect the floor are the ones where they
     * said the opposite.
     */
    public Money cutFloor() {
        return switch (rigidity) {
            case DISPOSABLE -> Money.ZERO;
            case FLEXIBLE, ESSENTIAL -> floor;
            case LOCKED -> amount;
        };
    }

    /** How much this line could give up, which is zero for anything it may not give from. */
    public Money headroom() {
        return mayGive() ? amount.minus(cutFloor()).max(Money.ZERO) : Money.ZERO;
    }
}
