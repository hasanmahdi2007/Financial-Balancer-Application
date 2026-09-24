package com.hasan.budget.planning.web;

import com.hasan.budget.planning.application.Keys;
import com.hasan.budget.planning.application.PlanService;
import com.hasan.budget.planning.application.PlanView.KeyLabel;
import com.hasan.budget.planning.domain.surplus.ItemScope;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.web.CurrentUser;
import com.hasan.budget.web.NameLength;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the user says they spend, and the commitments they have named themselves.
 *
 * <p>A category left out is not zero: the plan assumes the local figure for it and says so on that
 * line. Zero and "I have not said" are different answers, and treating the second as the first would
 * quietly overstate what the user has left.
 */
@RestController
@RequestMapping("/api/v1")
class SpendingController {

    private final PlanService plans;

    SpendingController(PlanService plans) {
        this.plans = plans;
    }

    /**
     * @param howWilling the user's own answer about this one thing, which the plan obeys absolutely
     * @param kind whether the amount is already inside its category's figure or sits on top of it.
     *     Asked rather than guessed: the two look identical in the data, and guessing wrong either
     *     counts the money twice or loses it.
     */
    record LineItemRequest(
            String label, String category, BigDecimal amount, String howWilling, String kind) {}

    record LineItemView(
            String id, String label, KeyLabel category, String amount, KeyLabel howWilling, KindView kind) {}

    record KindView(String key, String label, String meaning) {}

    @GetMapping("/spending")
    Map<String, String> spending(@CurrentUser String userId) {
        Map<String, String> stated = new LinkedHashMap<>();
        plans.spending(userId).forEach((category, amount) -> stated.put(Keys.of(category), amount.toString()));
        return stated;
    }

    @PutMapping("/spending")
    Map<String, String> saveSpending(@CurrentUser String userId, @RequestBody Map<String, BigDecimal> request) {
        Map<SpendCategory, Money> stated = new LinkedHashMap<>();
        request.forEach((key, amount) -> {
            if (amount == null) {
                throw new IllegalArgumentException(
                        "Give an amount for \"" + key + "\", or leave it out to use the typical figure where you live.");
            }
            stated.put(Keys.parse(SpendCategory.class, key, "kind of spending"), new Money(amount));
        });
        plans.saveSpending(userId, stated);
        return spending(userId);
    }

    @GetMapping("/line-items")
    List<LineItemView> lineItems(@CurrentUser String userId) {
        return plans.lineItems(userId).stream().map(SpendingController::view).toList();
    }

    @PutMapping("/line-items/{id}")
    LineItemView saveLineItem(
            @CurrentUser String userId, @PathVariable String id, @RequestBody LineItemRequest request) {
        if (request.label() == null || request.label().isBlank()) {
            throw new IllegalArgumentException("Give this a name you will recognise, such as \"Gym membership\".");
        }
        NameLength.check(request.label(), "the name");
        if (request.amount() == null) {
            throw new IllegalArgumentException("Say how much this costs each month.");
        }
        SpendCategory category = Keys.parse(SpendCategory.class, request.category(), "kind of spending");
        return view(plans.saveLineItem(userId, new UserLineItem(
                id,
                request.label().strip(),
                category,
                new Money(request.amount()),
                request.howWilling() == null
                        ? category.defaultRigidity()
                        : Keys.parse(Rigidity.class, request.howWilling(), "answer about changing this"),
                Keys.parse(ItemScope.class, request.kind(), "kind of commitment"))));
    }

    @DeleteMapping("/line-items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteLineItem(@CurrentUser String userId, @PathVariable String id) {
        plans.deleteLineItem(userId, id);
    }

    private static LineItemView view(UserLineItem item) {
        return new LineItemView(
                item.id(),
                item.label(),
                new KeyLabel(Keys.of(item.parent()), item.parent().label()),
                item.monthlyAmount().toString(),
                new KeyLabel(Keys.of(item.rigidity()),
                        com.hasan.budget.planning.application.Wording.howWilling(item.rigidity()).label()),
                new KindView(
                        Keys.of(item.scope()), item.scope().labelFor(item.parent()), item.scope().means()));
    }
}
