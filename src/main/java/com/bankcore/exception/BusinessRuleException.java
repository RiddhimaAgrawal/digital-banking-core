package com.bankcore.exception;

import org.springframework.http.HttpStatus;

/** The request is well-formed but a banking rule forbids it (HTTP 422). */
public class BusinessRuleException extends BankException {
    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
