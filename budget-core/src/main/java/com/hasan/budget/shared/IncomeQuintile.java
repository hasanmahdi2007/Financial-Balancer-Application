package com.hasan.budget.shared;

/**
 * Which fifth of the income distribution a user falls into.
 *
 * <p>The split is essential rather than decorative: someone on $40k and someone on $150k have
 * nothing in common on housing, so a single national average would produce a baseline useful to
 * neither. BLS publishes the Consumer Expenditure Survey by quintile for exactly this reason.
 *
 * <p><strong>Known mismatch, to be handled rather than ignored.</strong> BLS quintiles are defined on
 * <em>pre-tax household</em> income, while this product collects <em>net personal</em> income and is
 * explicitly single-person, against a CEX average household of about 2.5 people. Assigning a user by
 * their net figure will place many of them too low, and the resulting baselines read high on both
 * counts. Any correction belongs in data - a per-capita factor on the country row - never as a
 * constant buried in code, and the assumption belongs in the README.
 */
public enum IncomeQuintile {
    Q1,
    Q2,
    Q3,
    Q4,
    Q5
}
