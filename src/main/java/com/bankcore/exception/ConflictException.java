package com.bankcore.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends BankException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
