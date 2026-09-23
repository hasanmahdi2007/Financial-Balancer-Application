package com.hasan.budget.ingestion.support;

import com.hasan.budget.ingestion.domain.SyncResult;
import com.hasan.budget.ingestion.port.BankDataProvider;
import java.util.ArrayList;
import java.util.List;

/**
 * A real provider with a notebook: it records which cursor each sync asked from.
 *
 * <p>The cursor is the whole of "a restart resumes rather than re-importing", and the only way to
 * see it is to watch what gets asked for. Counting stored rows cannot tell the difference, because
 * re-importing the same history is idempotent and leaves an identical ledger - which is exactly why
 * a durable cursor could be broken for months without any test noticing.
 */
public class RecordingBankDataProvider implements BankDataProvider {

    private final BankDataProvider delegate;
    private final List<String> cursorsAskedFor = new ArrayList<>();

    public RecordingBankDataProvider(BankDataProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public String exchangePublicToken(String publicToken) {
        return delegate.exchangePublicToken(publicToken);
    }

    @Override
    public SyncResult sync(String accessToken, String cursorOrNull) {
        cursorsAskedFor.add(cursorOrNull);
        return delegate.sync(accessToken, cursorOrNull);
    }

    /** Null entries mean a full import was requested. */
    public List<String> cursorsAskedFor() {
        return List.copyOf(cursorsAskedFor);
    }

    public void forget() {
        cursorsAskedFor.clear();
    }
}
