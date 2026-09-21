package com.hasan.budget.costofliving.domain;

/**
 * Whether a figure the user typed is backed by evidence, or only by their say-so.
 *
 * <p>This is the validator that works at one user, and therefore the only one that works today.
 * Quorum and outlier rejection need a crowd; a person's own bank statement does not, and it is
 * stronger evidence than twenty opinions - it checks the claim against what actually left their
 * account.
 */
public enum Corroboration {
    /** No bank connection, or no recurring stream detected for this category. */
    NOT_CHECKED,
    /** The user's own transactions agree with the claim, within tolerance. */
    CORROBORATED,
    /** The user's own transactions disagree. Their figure still applies to them - it is theirs. */
    CONTRADICTED
}
