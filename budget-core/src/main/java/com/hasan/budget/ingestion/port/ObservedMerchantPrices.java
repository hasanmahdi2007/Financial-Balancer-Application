package com.hasan.budget.ingestion.port;

import com.hasan.budget.ingestion.domain.MerchantAverage;
import com.hasan.budget.shared.SpendCategory;
import java.time.YearMonth;
import java.util.List;

/**
 * What the user's own transactions say their regular merchants cost.
 *
 * <p>This exists as a port rather than as a repository other modules reach into because the module
 * that wants the answer may not import this one - {@code planning.domain} is forbidden from
 * depending on {@code ingestion} and an architecture rule enforces it. A narrow interface returning
 * one small shape is what lets the affordability check use real observed prices without Plaid's idea
 * of a merchant reaching the engine.
 *
 * <p>An empty answer is a normal answer, not a failure: a user who has just connected their bank has
 * no history yet, and the caller is expected to fall back to an estimate and say that it is one.
 */
public interface ObservedMerchantPrices {

    /**
     * Average ticket and sample size per merchant, for one category in one month.
     *
     * @param month passed in rather than read from a clock, so the same question always has the same
     *     answer
     */
    List<MerchantAverage> averagesFor(String userId, SpendCategory category, YearMonth month);
}
