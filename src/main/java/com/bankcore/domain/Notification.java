package com.bankcore.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Notification(
        long id,
        UUID customerId,
        UUID eventId,
        String kind,
        String message,
        OffsetDateTime createdAt) {
}
