package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.util.List;
import java.util.Objects;

/**
 * The question Path A has to ask, carrying everything needed to ask it honestly.
 *
 * <p>Connecting a bank shows the app every account the user has, including money they never chose
 * to show it, so it has to ask what is in scope. That makes the question sound like an interrogation
 * unless it says why it is being asked - which is the same requirement the floor question carries,
 * and the reason this is a type rather than a string in a template.
 *
 * <p>There is deliberately no equivalent for Path B. A user who typed a figure already answered this
 * by choosing what to type, so the question is not merely skipped there: it cannot be built at all,
 * because there are no visible accounts to build it from.
 *
 * @param accountsWeCanSee every account, excluded ones included, because the point of the question
 *     is to show the user exactly what the app can see before asking how much of it to use
 * @param visibleBalance the total across those accounts. Not formatted here - how to render an
 *     amount is the interface's business, and a domain that formatted money would have to know
 *     about locales.
 */
public record ConsideredFundsQuestion(
        String question, String why, List<BankAccountBalance> accountsWeCanSee, Money visibleBalance) {

    public ConsideredFundsQuestion {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(why, "why");
        Objects.requireNonNull(visibleBalance, "visibleBalance");
        accountsWeCanSee = List.copyOf(accountsWeCanSee);
        if (accountsWeCanSee.isEmpty()) {
            throw new IllegalArgumentException(
                    "there is nothing to ask about until an account is connected");
        }
    }

    /**
     * Asked once a bank is connected, before the user has chosen a share or a figure.
     *
     * <p>Either answer is acceptable and the wording says so, because "60%" and "$50,000" are the
     * same decision expressed the two ways people actually think about their own money.
     */
    public static ConsideredFundsQuestion forAccounts(List<BankAccountBalance> accounts) {
        Money visible = accounts.stream()
                .map(BankAccountBalance::balance)
                .reduce(Money.ZERO, Money::plus);
        return new ConsideredFundsQuestion(
                "How much of this money should the plan work with?",
                "We can see everything in the accounts you connected, and some of it may not be "
                        + "yours to spend. Answer with a share of it or with an amount - whichever "
                        + "you think in - and we will leave the rest alone.",
                accounts,
                visible);
    }

    /**
     * One rendered line per account, for example {@code "Current account"} or
     * {@code "Joint account - you asked us to leave this one out"}.
     *
     * <p>Named by whatever the bank calls them, so an account the user renamed reads back the way
     * they named it. Already-excluded accounts stay in the list and say so, rather than being
     * dropped: a user cannot sensibly answer "how much of this?" while looking at a total that
     * disagrees with their own bank, and quietly omitting a row is how that happens.
     */
    public List<String> explainedCoverage() {
        return accountsWeCanSee.stream()
                .map(account -> account.excluded()
                        ? account.label() + " - you asked us to leave this one out"
                        : account.label())
                .toList();
    }
}
