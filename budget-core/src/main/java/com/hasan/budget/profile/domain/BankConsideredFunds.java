package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Path A: a bank is connected, so the app can see money the user never chose to show it.
 *
 * <p>That visibility is what makes the share question necessary rather than nosy. The balance is
 * reduced in a fixed order - drop excluded accounts, drop flagged one-off deposits, then apply the
 * user's share - and every dollar removed along the way is reported in {@link #setAsideBreakdown()} instead
 * of quietly vanishing.
 *
 * <pre>
 * pool       = Σ non-excluded balances − ring-fenced deposits
 * considered = pool × pct                 (PERCENTAGE)
 * considered = min(stated, pool)          (ABSOLUTE)
 * </pre>
 *
 * <p>The {@code min} is the whole point of the absolute form. A user who names a figure above their
 * real balance is planning against money that is not there, so the figure is clamped and
 * {@link #clampedToAvailableBalance()} says so rather than letting the plan quietly inherit an
 * impossible starting point.
 *
 * @param monthlyDeposits the deposits seen over the month being planned. Income is what is left of
 *     them once excluded accounts and flagged one-offs are removed, which is what keeps a gift from
 *     the family out of the surplus.
 * @param statedAmount the named figure, for {@link ConsiderationMode#ABSOLUTE}; null otherwise
 * @param share the share in scope, for {@link ConsiderationMode#PERCENTAGE}; null otherwise
 */
public record BankConsideredFunds(
        List<BankAccountBalance> accounts,
        List<Deposit> monthlyDeposits,
        ConsiderationMode mode,
        Money statedAmount,
        Rate share)
        implements ConsideredFundsSource {

    public BankConsideredFunds {
        Objects.requireNonNull(mode, "mode");
        accounts = List.copyOf(accounts);
        monthlyDeposits = List.copyOf(monthlyDeposits);
        switch (mode) {
            case PERCENTAGE -> {
                Objects.requireNonNull(share, "a percentage of the balance needs a share");
                if (statedAmount != null) {
                    throw new IllegalArgumentException("a percentage share cannot also name a figure");
                }
            }
            case ABSOLUTE -> {
                Objects.requireNonNull(statedAmount, "an absolute figure needs an amount");
                if (share != null) {
                    throw new IllegalArgumentException("a named figure cannot also carry a percentage");
                }
                if (statedAmount.isNegative()) {
                    throw new IllegalArgumentException(
                            "statedAmount must not be negative but was " + statedAmount);
                }
            }
            case WHOLE -> throw new IllegalArgumentException(
                    "a connected bank can see more than the user chose to show, so it must ask what "
                            + "share is in scope; taking the whole balance is the manual path");
        }
    }

    /** "Plan with 60% of what you have." */
    public static BankConsideredFunds share(
            List<BankAccountBalance> accounts, List<Deposit> monthlyDeposits, Rate share) {
        return new BankConsideredFunds(
                accounts, monthlyDeposits, ConsiderationMode.PERCENTAGE, null, share);
    }

    /** "Plan with $50,000 of what you have", clamped to what is actually there. */
    public static BankConsideredFunds upTo(
            List<BankAccountBalance> accounts, List<Deposit> monthlyDeposits, Money statedAmount) {
        return new BankConsideredFunds(
                accounts, monthlyDeposits, ConsiderationMode.ABSOLUTE, statedAmount, null);
    }

    @Override
    public ConsideredFunds resolve() {
        return new ConsideredFunds(
                consideredBalance(), monthlyIncome(), setAsideBreakdown().total(), mode);
    }

    /** What the plan is allowed to see. */
    public Money consideredBalance() {
        Money pool = pool();
        return switch (mode) {
            case PERCENTAGE -> share.applyTo(pool);
            case ABSOLUTE -> statedAmount.min(pool);
            case WHOLE -> throw new IllegalStateException("unreachable: rejected at construction");
        };
    }

    /**
     * True when the user named more than they have. Worth surfacing: the difference between "we are
     * planning with your $50,000" and "we are planning with the $31,200 you actually hold" is the
     * difference between a plan and a wish.
     */
    public boolean clampedToAvailableBalance() {
        return mode == ConsiderationMode.ABSOLUTE && statedAmount.compareTo(pool()) > 0;
    }

    /**
     * Income for the month, after the two kinds of money the user asked us to leave alone. An
     * excluded account's deposits never counted; a flagged deposit is removed by name.
     */
    public Money monthlyIncome() {
        return monthlyDeposits.stream()
                .filter(deposit -> !deposit.ringFenced())
                .filter(deposit -> !excludedAccountIds().contains(deposit.accountId()))
                .map(Deposit::amount)
                .reduce(Money.ZERO, Money::plus);
    }

    /**
     * The three routes to "not in scope", kept apart so the interface can say which one applied.
     *
     * <p>A flagged deposit inside an excluded account is counted once, under the account: the
     * account's balance already contains it, and counting both would overstate the set-aside total
     * and break the identity that every visible dollar is either considered or explained.
     */
    public SetAside setAsideBreakdown() {
        Money excludedAccounts = accounts.stream()
                .filter(BankAccountBalance::excluded)
                .map(BankAccountBalance::balance)
                .reduce(Money.ZERO, Money::plus);
        Money flagged = flaggedDepositsInVisibleAccounts();
        Money withheld = pool().minus(consideredBalance());
        return new SetAside(excludedAccounts, flagged, withheld);
    }

    /** Everything the bank reports, before any of it is set aside. */
    public Money visibleBalance() {
        return accounts.stream().map(BankAccountBalance::balance).reduce(Money.ZERO, Money::plus);
    }

    /** Non-excluded balances less flagged one-offs: the most the user could put in scope. */
    private Money pool() {
        Money included = accounts.stream()
                .filter(account -> !account.excluded())
                .map(BankAccountBalance::balance)
                .reduce(Money.ZERO, Money::plus);
        return included.minus(flaggedDepositsInVisibleAccounts()).max(Money.ZERO);
    }

    private Money flaggedDepositsInVisibleAccounts() {
        Set<String> excluded = excludedAccountIds();
        return monthlyDeposits.stream()
                .filter(Deposit::ringFenced)
                .filter(deposit -> !excluded.contains(deposit.accountId()))
                .map(Deposit::amount)
                .reduce(Money.ZERO, Money::plus);
    }

    private Set<String> excludedAccountIds() {
        return accounts.stream()
                .filter(BankAccountBalance::excluded)
                .map(BankAccountBalance::accountId)
                .collect(Collectors.toSet());
    }
}
