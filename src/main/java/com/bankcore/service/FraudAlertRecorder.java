package com.bankcore.service;

import com.bankcore.exception.FraudRuleViolationException;
import com.bankcore.repository.FraudRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves a fraud alert. It is called by {@link TransferService} AFTER the blocked transfer's transaction has rolled
 * back, so it runs in its own short transaction. (Writing it inside the transfer transaction would lose it on
 * rollback; writing it in a nested REQUIRES_NEW transaction would need a second pooled connection while the first
 * is still held, which can exhaust the pool under load.)
 */
@Component
public class FraudAlertRecorder {

    private final FraudRepository alerts;

    public FraudAlertRecorder(FraudRepository alerts) {
        this.alerts = alerts;
    }

    @Transactional
    public void record(FraudRuleViolationException violation) {
        alerts.insert(violation.getCustomerId(), violation.getAccountId(), violation.getRule(),
                violation.getMessage(), violation.getAmount());
    }
}
