package com.bankcore.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A bank account. {@code balance} is a cached total that is always updated in the same database
 * transaction as the ledger entries; the ledger remains the source of truth.
 */
public record Account(
        UUID id,
        UUID customerId,
        String accountNumber,
        AccountType type,
        String currency,
        BigDecimal balance,
        boolean allowNegative,
        AccountStatus status,
        OffsetDateTime createdAt) {

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
