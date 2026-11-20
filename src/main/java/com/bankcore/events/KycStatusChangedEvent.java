package com.bankcore.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Published to Kafka whenever a customer's KYC status changes (submitted, approved, rejected). */
public record KycStatusChangedEvent(
        UUID eventId,
        String eventType,              // KYC_SUBMITTED | KYC_APPROVED | KYC_REJECTED
        UUID customerId,
        String status,
        String note,
        OffsetDateTime occurredAt) {
}
