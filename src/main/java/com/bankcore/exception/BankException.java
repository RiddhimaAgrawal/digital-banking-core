package com.bankcore.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class of all expected, business-level errors. Each carries the HTTP status and a stable
 * machine-readable {@code code} that API clients can switch on. GlobalExceptionHandler turns it into
 * an RFC 7807 problem response. Because it is a RuntimeException, Spring rolls the database
 * transaction back when one is thrown.
 */
public class BankException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BankException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
