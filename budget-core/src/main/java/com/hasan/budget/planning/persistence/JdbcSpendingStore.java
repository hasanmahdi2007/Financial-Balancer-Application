package com.hasan.budget.planning.persistence;

import com.hasan.budget.planning.application.SpendingStore;
import com.hasan.budget.planning.domain.surplus.ItemScope;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stated spending and named items. Every statement carries the user id in its WHERE clause or key. */
@Repository
public class JdbcSpendingStore implements SpendingStore {

    private final JdbcClient jdbc;

    public JdbcSpendingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<SpendCategory, Money> spending(String userId) {
        Map<SpendCategory, Money> stated = new EnumMap<>(SpendCategory.class);
        jdbc.sql("SELECT category, amount FROM planning_spending WHERE user_id = :userId")
                .param("userId", userId)
                .query(rs -> {
                    stated.put(SpendCategory.valueOf(rs.getString("category")), new Money(rs.getBigDecimal("amount")));
                });
        return Collections.unmodifiableMap(stated);
    }

    @Override
    @Transactional
    public void replaceSpending(String userId, Map<SpendCategory, Money> spending) {
        jdbc.sql("DELETE FROM planning_spending WHERE user_id = :userId").param("userId", userId).update();
        spending.forEach((category, amount) -> jdbc.sql(
                        "INSERT INTO planning_spending (user_id, category, amount) VALUES (:userId, :category, :amount)")
                .param("userId", userId)
                .param("category", category.name())
                .param("amount", amount.amount())
                .update());
    }

    @Override
    public List<UserLineItem> lineItems(String userId) {
        return jdbc.sql("""
                        SELECT id, label, category, amount, rigidity, scope
                          FROM planning_line_item
                         WHERE user_id = :userId
                         ORDER BY id
                        """)
                .param("userId", userId)
                .query((rs, row) -> new UserLineItem(
                        rs.getString("id"),
                        rs.getString("label"),
                        SpendCategory.valueOf(rs.getString("category")),
                        new Money(rs.getBigDecimal("amount")),
                        Rigidity.valueOf(rs.getString("rigidity")),
                        ItemScope.valueOf(rs.getString("scope"))))
                .list();
    }

    @Override
    public void saveLineItem(String userId, UserLineItem item) {
        jdbc.sql("""
                        INSERT INTO planning_line_item (user_id, id, label, category, amount, rigidity, scope)
                        VALUES (:userId, :id, :label, :category, :amount, :rigidity, :scope)
                        ON CONFLICT (user_id, id) DO UPDATE
                           SET label = EXCLUDED.label, category = EXCLUDED.category, amount = EXCLUDED.amount,
                               rigidity = EXCLUDED.rigidity, scope = EXCLUDED.scope
                        """)
                .param("userId", userId)
                .param("id", item.id())
                .param("label", item.label())
                .param("category", item.parent().name())
                .param("amount", item.monthlyAmount().amount())
                .param("rigidity", item.rigidity().name())
                .param("scope", item.scope().name())
                .update();
    }

    @Override
    public boolean deleteLineItem(String userId, String itemId) {
        return jdbc.sql("DELETE FROM planning_line_item WHERE user_id = :userId AND id = :id")
                        .param("userId", userId)
                        .param("id", itemId)
                        .update()
                > 0;
    }
}
