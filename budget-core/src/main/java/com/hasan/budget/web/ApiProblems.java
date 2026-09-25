package com.hasan.budget.web;

import com.hasan.budget.ingestion.application.BankNotOfferedException;
import com.hasan.budget.ingestion.application.BankUnavailableException;
import com.hasan.budget.planning.application.ChooseAPlanException;
import com.hasan.budget.planning.application.NeedsMoreInformationException;
import com.hasan.budget.planning.application.NotFoundException;
import com.hasan.budget.web.CurrentUserArgumentResolver.NotSignedInException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every failure, as an RFC 9457 problem document.
 *
 * <p>The {@code detail} of each one is written to be shown to the user unchanged, which is why the
 * exceptions carry sentences rather than codes. A person who is told "that city is not in our list"
 * can act; one who is told "IllegalArgumentException" cannot, and a client that has to translate
 * messages ends up owning a second copy of wording that then drifts.
 *
 * <p>Nothing here reports whether a thing exists for somebody else. A goal belonging to another user
 * and a goal that never existed both answer the same way, so ids cannot be probed.
 */
@RestControllerAdvice
class ApiProblems {

    private static final String TYPES = "https://financialbalancer.app/problems/";

    @ExceptionHandler(NotSignedInException.class)
    ProblemDetail notSignedIn(NotSignedInException problem) {
        return detail(HttpStatus.UNAUTHORIZED, "not-signed-in", "Sign in", problem.getMessage());
    }

    /** The bank or our access to it failed. 503: nothing about the request was wrong, and a retry may work. */
    @ExceptionHandler(BankUnavailableException.class)
    ProblemDetail bankUnavailable(BankUnavailableException problem) {
        return detail(
                HttpStatus.SERVICE_UNAVAILABLE, "bank-unavailable", "We could not reach your bank", problem.getMessage());
    }

    /** Connecting a bank is not available where this user lives. 409: the request is fine, their situation is not. */
    @ExceptionHandler(BankNotOfferedException.class)
    ProblemDetail bankNotOffered(BankNotOfferedException problem) {
        return detail(HttpStatus.CONFLICT, "bank-not-offered", "Connecting a bank is not available", problem.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException problem) {
        return detail(HttpStatus.NOT_FOUND, "not-found", "We could not find that", problem.getMessage());
    }

    /**
     * The user has not told us something a plan depends on. 409 rather than 400: the request itself is
     * fine, and it is the state of their account that is not ready yet.
     */
    @ExceptionHandler(NeedsMoreInformationException.class)
    ProblemDetail needsMore(NeedsMoreInformationException problem) {
        return detail(
                HttpStatus.CONFLICT, "needs-more-information", "We need a little more first", problem.getMessage());
    }

    /**
     * A distinct type rather than another conflict, because the client does something different with
     * it: it opens the plan chooser for the new place instead of showing the sentence as a problem.
     */
    @ExceptionHandler(ChooseAPlanException.class)
    ProblemDetail chooseAPlan(ChooseAPlanException problem) {
        return detail(HttpStatus.CONFLICT, "choose-a-plan", "Choose a plan for where you live now", problem.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException problem) {
        return detail(HttpStatus.BAD_REQUEST, "invalid-request", "That does not look right", problem.getMessage());
    }

    /** Malformed JSON, or a field of the wrong shape. The cause is not echoed back: it is not readable. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException problem) {
        return detail(
                HttpStatus.BAD_REQUEST,
                "invalid-request",
                "That does not look right",
                "We could not read that request. Check the fields and their formats against the API contract.");
    }

    private static ProblemDetail detail(HttpStatus status, String type, String title, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setType(URI.create(TYPES + type));
        problem.setTitle(title);
        return problem;
    }
}
