package com.bankcore.service;

import com.bankcore.domain.BankTransaction;

/**
 * @param transaction the money movement
 * @param replayed    true if this was a retry of an earlier request (same Idempotency-Key) and nothing new was booked
 */
public record TransferResult(BankTransaction transaction, boolean replayed) {
}
