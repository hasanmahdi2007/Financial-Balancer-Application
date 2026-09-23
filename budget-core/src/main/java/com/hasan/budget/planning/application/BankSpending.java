package com.hasan.budget.planning.application;

import com.hasan.budget.planning.domain.decision.ObservedTicket;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the user's own bank data can say about a month, and nothing more.
 *
 * <p>Two questions, because the affordability check asks exactly two of a bank: what this person
 * actually pays at places like this, and what has already gone on that category this month. Keeping
 * the seam this narrow is what stops the ingestion module's idea of a transaction reaching the
 * decision engine, which may not import it at all.
 *
 * <p>Both answers are allowed to be absent. Nobody has to connect a bank, and a product that only
 * works once they do would be useless on the day they sign up: with no observations every price is an
 * estimate and says so, and what has been spent so far is asked for instead.
 */
public interface BankSpending {

    /**
     * Averages from the user's own payments, for pricing the options on offer.
     *
     * <p>Only where spending on a category is measured against real purchases does this mean
     * anything, which today is eating out; everywhere else it is empty and the estimate stands.
     */
    List<ObservedTicket> observedTickets(String userId, SpendCategory category, YearMonth month);

    /** What has gone on that category this month, when a bank can say. Empty when none is connected. */
    Optional<Money> spentThisMonth(String userId, SpendCategory category, YearMonth month);

    /** For a user with no bank connected, and for tests about anything other than bank data. */
    BankSpending NONE = new BankSpending() {

        @Override
        public List<ObservedTicket> observedTickets(String userId, SpendCategory category, YearMonth month) {
            return List.of();
        }

        @Override
        public Optional<Money> spentThisMonth(String userId, SpendCategory category, YearMonth month) {
            return Optional.empty();
        }
    };

    /**
     * Categories whose spending is made up of individual purchases a price ladder can be built from.
     * Rent is not one of them: averaging "what you usually pay for rent" over payments tells the user
     * their rent, which they know.
     */
    Set<SpendCategory> PRICED_BY_THE_TICKET = Set.of(SpendCategory.DINING_OUT);
}
