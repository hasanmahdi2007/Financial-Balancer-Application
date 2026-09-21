package com.hasan.budget.profile.domain;

/**
 * Where the money the app may plan with came from, and how much of it is in scope.
 *
 * <p>Sealed over exactly two cases because there are exactly two ways money reaches this product,
 * and they differ in one question: whether the app can see money the user never chose to show it.
 * A connected bank can, so it must ask what share is in scope. A user typing a figure has already
 * answered that question by choosing what to type, and asking again would be incoherent.
 *
 * <p>Both produce the same {@link ConsideredFunds}, so nothing downstream branches on the entry
 * path. That is the point of the seam: the plan is built from one shape whichever route produced it.
 */
public sealed interface ConsideredFundsSource permits BankConsideredFunds, ManualConsideredFunds {

    /** The money the app may plan with, the income it may plan from, and what was set aside. */
    ConsideredFunds resolve();
}
