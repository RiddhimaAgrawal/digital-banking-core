package com.bankcore.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends BankException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
