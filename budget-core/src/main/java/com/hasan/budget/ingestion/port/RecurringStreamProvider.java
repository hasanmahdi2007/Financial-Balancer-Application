package com.hasan.budget.ingestion.port;

import com.hasan.budget.ingestion.domain.RecurringStream;
import java.util.List;

/**
 * Repeating payments and deposits, as detected by whoever holds the bank data.
 *
 * <p>Deliberately not part of {@link BankDataProvider}. Detecting recurrence is a capability rather
 * than a given - a regional provider may return transactions and nothing else - and a provider that
 * cannot do it should be unable to implement this interface rather than obliged to return an empty
 * list and let callers wonder whether the user really has no subscriptions.
 *
 * <p>Worth buying rather than building: separating rent, insurance and subscriptions from variable
 * spending is most of what a plan needs from history, and a detector written here would be guessing
 * from three months of data where the provider is looking at far more.
 */
public interface RecurringStreamProvider {

    List<RecurringStream> recurringStreams(String accessToken);
}
