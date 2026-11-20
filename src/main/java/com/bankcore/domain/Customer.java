package com.bankcore.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A bank customer, linked to a Keycloak user through the JWT "sub" claim. */
public record Customer(
        UUID id,
        String keycloakUserId,
        String fullName,
        String email,
        KycStatus kycStatus,
        OffsetDateTime createdAt) {

    public boolean kycApproved() {
        return kycStatus == KycStatus.APPROVED;
    }
}
