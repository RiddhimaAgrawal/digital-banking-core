package com.bankcore.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe view of the {@code bank.*} section of application.yml. */
@ConfigurationProperties(prefix = "bank")
public record BankProperties(String currency, Fraud fraud, Outbox outbox, Statements statements) {

    public record Fraud(
            BigDecimal maxTransferAmount,
            BigDecimal dailyLimit,
            int velocityMaxTransfers,
            int velocityWindowSeconds) {}

    public record Outbox(boolean enabled, long pollIntervalMs, int batchSize) {}

    public record Statements(String cron) {}
}
