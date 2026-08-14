package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.ledger.ConflictingTransactionException;
import am.ankap.ledgerflow.payment.IllegalStateTransitionException;
import am.ankap.ledgerflow.payment.PaymentNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
class PaymentExceptionHandler {

    private static final String BASE = "https://ledgerflow.dev/errors/";

    @ExceptionHandler(IdempotencyKeyInUseException.class)
    ResponseEntity<ProblemDetail> handleKeyInUse(IdempotencyKeyInUseException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Request in progress", e.getMessage(),
                "idempotency-key-in-use");
        problem.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(problem);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ProblemDetail handleKeyConflict(IdempotencyKeyConflictException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Idempotency key reused", e.getMessage(),
                "idempotency-key-conflict");
        problem.setProperty("retryable", false);
        return problem;
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalStateTransitionException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Invalid payment state", e.getMessage(),
                "invalid-state-transition");
        problem.setProperty("retryable", false);
        return problem;
    }

    @ExceptionHandler(ConflictingTransactionException.class)
    ProblemDetail handleLedgerConflict(ConflictingTransactionException e) {
        return problem(HttpStatus.CONFLICT, "Ledger conflict", e.getMessage(), "ledger-conflict");
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail handleNotFound(PaymentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Payment not found", e.getMessage(), "payment-not-found");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage(), "invalid-request");
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "Invalid state", e.getMessage(), "invalid-state");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE + type));
        return problem;
    }
}