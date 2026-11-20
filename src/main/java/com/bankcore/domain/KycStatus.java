package com.bankcore.domain;

/** Where a customer is in the KYC workflow. NOT_SUBMITTED -> PENDING -> APPROVED | REJECTED (-> PENDING again on resubmit). */
public enum KycStatus {
    NOT_SUBMITTED, PENDING, APPROVED, REJECTED
}
