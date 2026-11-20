package com.bankcore.web;

import com.bankcore.exception.BankException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into a consistent RFC 7807 "problem" JSON body:
 * <pre>{ "title": "Unprocessable Entity", "status": 422, "detail": "...", "code": "INSUFFICIENT_FUNDS" }</pre>
 * Clients switch on the stable {@code code}, not on the human text.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives correct problem responses for the standard Spring MVC
 * errors (404 unknown URL, 405 wrong method, 415, missing header, malformed JSON ...) for free.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BankException.class)
    public ResponseEntity<ProblemDetail> handleBank(BankException e) {
        return problem(e.getStatus(), e.getCode(), e.getMessage(), null);
    }

    /** Bean Validation failure on a @Valid request body: list each bad field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setTitle("Bad Request");
        pd.setProperty("code", "VALIDATION_FAILED");
        pd.setProperty("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException e) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage(), null);
    }

    /** Thrown by @PreAuthorize when the caller's role is not allowed. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleDenied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role does not allow this operation", null);
    }

    /** Last line of defence: a database constraint tripped (duplicate row, CHECK failure, unbalanced ledger...). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "DATA_INTEGRITY", "The request conflicts with existing data", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", null);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                  Map<String, String> fields) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        if (fields != null) {
            pd.setProperty("fields", fields);
        }
        return ResponseEntity.status(status).body(pd);
    }
}
