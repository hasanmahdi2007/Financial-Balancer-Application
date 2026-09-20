package com.hasan.budget.shared;

/**
 * Which fifth of the income distribution a user falls into.
 *
 * <p>The split is essential rather than decorative: someone on $40k and someone on $150k have
 * nothing in common on housing, so a single national average would produce a baseline useful to
 * neither. BLS publishes the Consumer Expenditure Survey by quintile for exactly this reason.
 *
 * <p><strong>Two known mismatches, which pull in opposite directions.</strong> BLS quintiles are
 * defined on <em>pre-tax household</em> income while this product collects <em>net personal</em>
 * income, so assigning a user by their net figure places many of them a tier too low and the baseline
 * reads <em>low</em>. Against that, CEX figures describe a consumer unit averaging 2.5 people while
 * this product is explicitly single-person, which makes the same baseline read <em>high</em>. They
 * partially cancel, and the net direction cannot be known without measuring.
 *
 * <p>So measure before correcting. Packet P2 compares derived housing baselines against Census ACS
 * B25064 median gross rent, which is an independent anchor, and that comparison is what should size
 * the correction. Applying a theoretical factor first would mean tuning a number nobody has checked.
 *
 * <p>Corrections belong in data - an effective tax rate for the income basis, and per-category
 * household-size factors, since rent scales weakly with the number of people while groceries scale
 * almost linearly. Never a constant buried in code, and the assumption belongs in the README.
 */
public enum IncomeQuintile {
    Q1,
    Q2,
    Q3,
    Q4,
    Q5
}
