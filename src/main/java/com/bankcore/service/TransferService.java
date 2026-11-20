package com.bankcore.service;

import com.bankcore.domain.BankTransaction;
import com.bankcore.exception.FraudRuleViolationException;
import com.bankcore.security.Caller;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Public entry point for moving money. It is deliberately NOT transactional: the database transaction lives in
 * {@link TransferTransactions}. That split lets this class see the outcome AFTER the transaction has finished:
 * if a fraud rule blocked the transfer, the money movement is rolled back and only then is the fraud alert saved.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferTransactions tx;
    private final FraudAlertRecorder alerts;

    public TransferService(TransferTransactions tx, FraudAlertRecorder alerts) {
        this.tx = tx;
        this.alerts = alerts;
    }

    public TransferResult transfer(String idempotencyKey, String sourceNumber, String destinationNumber,
                                   BigDecimal amount, String description, Caller caller) {
        try {
            return tx.transfer(idempotencyKey, sourceNumber, destinationNumber, amount, description, caller);
        } catch (FraudRuleViolationException e) {
            log.warn("Fraud rule {} blocked a transfer: {}", e.getRule(), e.getMessage());
            alerts.record(e);
            throw e;
        }
    }

    public TransferResult deposit(String idempotencyKey, UUID accountId, BigDecimal amount,
                                  String description, Caller caller) {
        return tx.deposit(idempotencyKey, accountId, amount, description, caller);
    }

    public TransferResult withdraw(String idempotencyKey, UUID accountId, BigDecimal amount,
                                   String description, Caller caller) {
        return tx.withdraw(idempotencyKey, accountId, amount, description, caller);
    }

    public BankTransaction getVisible(UUID transactionId, Caller caller) {
        return tx.getVisible(transactionId, caller);
    }
}
