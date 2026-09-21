package com.hasan.budget.planning.domain.surplus;

import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    /**
     * The discretionary commitments the user has declared, summed per category, for whoever derives
     * the floor.
     *
     * <p>It exists because a {@code DISCRETIONARY} category is counted at zero here - the floor is
     * meant to cover all of them in one figure - so a declared "season ticket, $300" moves the
     * surplus by nothing at all unless the floor knows to absorb it. The floor is derived from
     * lifestyle tier and obligation load and cannot know that on its own, so it has to be told.
     *
     * <p>The filter lives next to the data rather than in each caller because getting it wrong is
     * silent in both directions. Include an {@link ItemScope#ALREADY_COUNTED} item and its money is
     * protected by the floor as well as sitting inside its category's total, which is the same
     * double count the scope axis exists to prevent. Include a non-discretionary one and it is
     * protected by the floor as well as being subtracted in its own right.
     *
     * <p>Returns one entry per category rather than a single total on purpose: raising a floor
     * against the total would let a $700 season ticket be answered by protecting groceries instead,
     * since the total would already be high enough.
     *
     * @return an unmodifiable map in category declaration order, empty when nothing was declared
     */
    public Map<SpendCategory, Money> declaredDiscretionaryCommitments() {
        Map<SpendCategory, Money> declared = new EnumMap<>(SpendCategory.class);
        for (UserLineItem item : lineItems) {
            if (item.scope().isSubtractedInItsOwnRight()
                    && item.parent().baselinePolicy() == BaselinePolicy.DISCRETIONARY) {
                declared.merge(item.parent(), item.monthlyAmount(), Money::plus);
            }
        }
        return Collections.unmodifiableMap(declared);
    }
}
