package com.bankcore.exception;

import org.springframework.http.HttpStatus;

public class KycRequiredException extends BankException {
    public KycRequiredException(String message) {
        super(HttpStatus.FORBIDDEN, "KYC_REQUIRED", message);
    }
}
