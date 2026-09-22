package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A repeating payment or deposit the provider has detected.
 *
 * <p>This is how rent, insurance and subscriptions get separated from variable spending without
 * writing a detector. What a stream is deliberately <strong>not</strong> used for is amounts: the
 * transactions remain the single source of what was actually spent, and a stream only says that
 * those particular rows repeat. The recorded sandbox is the reason - the ChatGPT subscription's
 * stream is tagged {@code TRANSFER_OUT_ACCOUNT_TRANSFER} while its own transactions say
 * {@code GENERAL_SERVICES}, so trusting the stream's own category would make a real subscription
 * disappear from spending altogether.
 *
 * @param label what to call this in front of a person - the merchant where there is one, so advice
 *     can say "cut your Netflix" rather than naming a category nobody recognises as theirs
 * @param lastAmount the most recent amount, in the provider's convention (positive is money out).
 *     The latest rather than the average, because a subscription that went up in price costs the new
 *     price next month and the average is already out of date.
 * @param nextExpected when the provider expects the next one, so a plan can speak about what is
 *     still to come this month rather than only about what has already gone
 * @param memberIds the transactions that make up this stream, which is what lets a payroll deposit
 *     tagged as a restaurant be recognised as pay
 */
public record RecurringStream(
        String streamId,
        String accountId,
        Direction direction,
        String label,
        Frequency frequency,
        Money lastAmount,
        LocalDate lastDate,
        LocalDate nextExpected,
        boolean active,
        List<String> memberIds) {

    public RecurringStream {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(lastAmount, "lastAmount");
        memberIds = List.copyOf(memberIds);
        if (label == null || label.isBlank()) {
            label = "Repeating payment";
        }
    }

    /** What this stream costs, or brings in, in a month. */
    public Money monthlyAmount() {
        return frequency.monthlyEquivalent(lastAmount);
    }

    /** Which way the money goes. */
    public enum Direction {
        /** Deposits. A merchant that pays the user every week is an employer, not a refund. */
        MONEY_IN,
        MONEY_OUT
    }
}
