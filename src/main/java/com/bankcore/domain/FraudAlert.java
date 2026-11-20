package com.bankcore.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FraudAlert(
        long id,
        UUID customerId,
        UUID accountId,
        String rule,
        String detail,
        BigDecimal amount,
        OffsetDateTime createdAt) {
}
