package com.portfolio.ledger.api;

import com.portfolio.ledger.domain.AccountNotFoundException;
import com.portfolio.ledger.domain.IdempotencyKeyReuseException;
import com.portfolio.ledger.domain.InsufficientFundsException;
import com.portfolio.ledger.market.MarketDataException;
import com.portfolio.ledger.trading.InsufficientHoldingsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 7807 problem responses (application/problem+json) with a stable "code" property.
 * Framework errors (malformed JSON, bad UUID, missing header) are handled by the parent class as 400s.
 * Unexpected errors return a generic 500 and are logged; their details are never sent to the client.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler({InsufficientFundsException.class, InsufficientHoldingsException.class})
    ResponseEntity<ProblemDetail> unprocessable(RuntimeException e) {
        String code = e instanceof InsufficientFundsException ? "INSUFFICIENT_FUNDS" : "INSUFFICIENT_HOLDINGS";
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, code, e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ResponseEntity<ProblemDetail> conflict(IdempotencyKeyReuseException e) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(MarketDataException.class)
    ResponseEntity<ProblemDetail> marketDown(MarketDataException e) {
        log.warn("Market data unavailable: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_DATA_UNAVAILABLE",
                "Market data is temporarily unavailable. Retry later.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(status.getReasonPhrase());
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
