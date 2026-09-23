package com.hasan.budget.planning.application;

import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.List;
import java.util.Map;

/**
 * What a user says they spend, and the commitments they have named. Every method is scoped to one
 * user: an item id is only unique within its owner's items, so no lookup here can take an id alone.
 */
public interface SpendingStore {

    Map<SpendCategory, Money> spending(String userId);

    /** Replaces the whole set, so a category the user removed stops being stated rather than lingering. */
    void replaceSpending(String userId, Map<SpendCategory, Money> spending);

    List<UserLineItem> lineItems(String userId);

    void saveLineItem(String userId, UserLineItem item);

    /** @return false when this user has no item with that id - including when another user does */
    boolean deleteLineItem(String userId, String itemId);
}
