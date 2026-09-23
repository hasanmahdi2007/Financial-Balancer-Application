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
 * <p>Three questions, and the product has no fourth to ask. Two of them are the affordability
 * check's: what this person actually pays at places like this, and what has already gone on that
 * category this month. The third is the plan's: what a whole finished month really cost. Keeping the
 * seam this narrow is what stops the ingestion module's idea of a transaction reaching the decision
 * engine, which may not import it at all.
 *
 * <p>Every one of them is allowed to be absent. Nobody has to connect a bank, and a product that only
 * works once they do would be useless on the day they sign up: with no observations every price is an
 * estimate and says so, what has been spent so far is asked for instead, and a plan is built from what
 * the user told us and the figures for where they live.
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

    /**
     * What a complete month actually cost, for a plan to be built on measurement rather than memory.
     *
     * <p>Separate from {@link #spentThisMonth} rather than looped over it because it is a different
     * question: that one asks about the month in progress, to answer "can I afford this today", and
     * this one asks about a month that is over, to answer "what does your life cost". Asking the
     * month in progress would price a life off however many days have passed.
     *
     * <p>Empty when no bank is connected or that month holds nothing, which is not a failure: the
     * plan falls back to what the user said, and then to the local figure.
     */
    MeasuredMonth measuredMonth(String userId, YearMonth month);

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

        @Override
        public MeasuredMonth measuredMonth(String userId, YearMonth month) {
            return MeasuredMonth.none(month);
        }
    };

    /**
     * Categories whose spending is made up of individual purchases a price ladder can be built from.
     * Rent is not one of them: averaging "what you usually pay for rent" over payments tells the user
     * their rent, which they know.
     */
    Set<SpendCategory> PRICED_BY_THE_TICKET = Set.of(SpendCategory.DINING_OUT);
}
