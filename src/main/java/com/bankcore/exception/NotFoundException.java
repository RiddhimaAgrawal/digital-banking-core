package com.bankcore.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends BankException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
