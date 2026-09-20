package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Everything the surplus calculation needs, and nothing else.
 *
 * @param alreadySaving money the user already moves into savings. Reported so they can see it, never
 *     subtracted: it is not spending, and subtracting it would count the same money twice once the
 *     goal it funds is planned for.
 * @param discretionaryFloor the quality-of-life minimum. Without one the engine happily recommends
 *     cutting all entertainment to zero, which is advice nobody follows.
 * @param asOf the evaluation date, passed in rather than read, so the same inputs always produce the
 *     same result
 */
public record SurplusInput(
        Money income,
        List<CategoryObservation> observations,
        List<UserLineItem> lineItems,
        Money alreadySaving,
        Money discretionaryFloor,
        LocalDate asOf) {

    public SurplusInput {
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(alreadySaving, "alreadySaving");
        Objects.requireNonNull(discretionaryFloor, "discretionaryFloor");
        Objects.requireNonNull(asOf, "asOf");
        observations = List.copyOf(observations);
        lineItems = List.copyOf(lineItems);
    }
}
