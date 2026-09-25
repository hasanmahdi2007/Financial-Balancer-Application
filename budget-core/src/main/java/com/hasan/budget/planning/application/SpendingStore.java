package com.hasan.budget.planning.application;

import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.List;
import java.util.Map;

/**
 * What a user says they spend in one plan, and the commitments they named there. Every method is
 * scoped to one plan of one user: an item id is a name the user chose, unique only within that plan.
 */
public interface SpendingStore {

    Map<SpendCategory, Money> spending(PlanKey plan);

    /** Replaces the whole set, so a category the user removed stops being stated rather than lingering. */
    void replaceSpending(PlanKey plan, Map<SpendCategory, Money> spending);

    List<UserLineItem> lineItems(PlanKey plan);

    void saveLineItem(PlanKey plan, UserLineItem item);

    /** @return false when this user has no item with that id - including when another user does */
    boolean deleteLineItem(PlanKey plan, String itemId);
}
