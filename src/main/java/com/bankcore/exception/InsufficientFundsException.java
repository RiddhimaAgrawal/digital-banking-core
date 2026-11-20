package com.bankcore.exception;

public class InsufficientFundsException extends BusinessRuleException {
    public InsufficientFundsException(String message) {
        super("INSUFFICIENT_FUNDS", message);
    }
}
