package com.splitledger.common.errors;

import com.splitledger.common.web.CorrelationIdFilter;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as RFC 7807 application/problem+json (LLD section 11.4),
 * carrying the correlation id so a user can quote one string and we can find the request.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Declared before the ApiException handler it specialises. A 429 without
     * {@code Retry-After} tells the client it was throttled but not when to come back,
     * so it usually retries at once and is throttled again.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException ex) {
        long seconds = Math.max(1, ex.retryAfter().toSeconds());
        return ResponseEntity.status(ex.code().status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(problem(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.code().status())
                .body(problem(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        // The message may contain internals, so it is logged and never returned.
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.title()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(ErrorCode.VALIDATION_ERROR.title());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(problem(ErrorCode.VALIDATION_ERROR, detail));
    }

    static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setDetail(detail);
        problem.setProperty("code", code.name());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
