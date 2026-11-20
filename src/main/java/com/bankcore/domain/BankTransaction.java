package com.bankcore.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One money movement: {@code amount} leaves {@code sourceAccountId} (debit) and arrives at {@code destinationAccountId} (credit). */
public record BankTransaction(
        UUID id,
        TransactionType type,
        String status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String description,
        String initiatedBy,
        OffsetDateTime createdAt) {
}
