package com.bankcore.exception;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A fraud rule blocked the transfer. Velocity breaches map to 429 (slow down), limit breaches to 422.
 * It carries the context needed to record a fraud alert AFTER the transfer's transaction has rolled back.
 */
public class FraudRuleViolationException extends BankException {

    private final String rule;
    private final UUID customerId;
    private final UUID accountId;
    private final BigDecimal amount;

    public FraudRuleViolationException(String rule, String message, UUID customerId, UUID accountId, BigDecimal amount) {
        super("VELOCITY".equals(rule) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNPROCESSABLE_ENTITY,
                "FRAUD_" + rule, message);
        this.rule = rule;
        this.customerId = customerId;
        this.accountId = accountId;
        this.amount = amount;
    }

    public String getRule() {
        return rule;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
