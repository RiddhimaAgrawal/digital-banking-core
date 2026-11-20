package com.bankcore.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One line of the ledger. Negative amount = debit (money out), positive = credit (money in). */
public record LedgerEntry(
        long id,
        UUID transactionId,
        UUID accountId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        OffsetDateTime createdAt) {
}
