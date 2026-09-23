package com.hasan.budget.ingestion.plaid;

/**
 * A call to Plaid that came back as an error.
 *
 * <p>The message is assembled from the endpoint, Plaid's own error code and its request id - enough
 * to find the call in Plaid's dashboard and fix it. What it deliberately never contains is the
 * request body, because every request carries an access token and an exception message is the most
 * reliable way for a secret to reach a log file, a monitoring tool and an error tracker all at once.
 */
public class PlaidApiException extends RuntimeException {

    private final String errorCode;

    public PlaidApiException(String path, String errorType, String errorCode, String requestId) {
        super("Plaid %s failed: %s / %s (request %s)".formatted(path, errorType, errorCode, requestId));
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
