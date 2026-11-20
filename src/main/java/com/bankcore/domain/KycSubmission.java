package com.bankcore.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record KycSubmission(
        UUID id,
        UUID customerId,
        DocumentType documentType,
        String documentNumber,
        LocalDate dateOfBirth,
        String address,
        String status,          // PENDING | APPROVED | REJECTED
        String reviewer,
        String reviewNote,
        OffsetDateTime submittedAt,
        OffsetDateTime reviewedAt) {
}
