package com.bankcore.events;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to Kafka after a money movement commits. {@code eventId} is unique and stable, so consumers
 * can de-duplicate (Kafka delivers at-least-once). Customer ids are null for the bank's own system account.
 */
public record TransactionCompletedEvent(
        UUID eventId,
        String eventType,              // TRANSFER_COMPLETED | DEPOSIT_COMPLETED | WITHDRAWAL_COMPLETED
        UUID transactionId,
        String transactionType,        // TRANSFER | DEPOSIT | WITHDRAWAL
        BigDecimal amount,
        String currency,
        UUID sourceAccountId,
        String sourceAccountNumber,
        UUID sourceCustomerId,
        UUID destinationAccountId,
        String destinationAccountNumber,
        UUID destinationCustomerId,
        String description,
        OffsetDateTime occurredAt) {
}
