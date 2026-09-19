package com.hasan.budget.planning.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;

/** A suggested monthly reduction that would close a funding gap. */
public record Tradeoff(SpendCategory category, Money suggestedReduction) {}
