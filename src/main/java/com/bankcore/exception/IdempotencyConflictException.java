package com.bankcore.exception;

/** The same Idempotency-Key was reused with a different request body. */
public class IdempotencyConflictException extends BusinessRuleException {
    public IdempotencyConflictException(String message) {
        super("IDEMPOTENCY_KEY_REUSED", message);
    }
}
