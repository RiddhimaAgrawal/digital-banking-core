package com.bankcore.events;

/** Kafka topic names. */
public final class Topics {

    /** One message per completed TRANSFER, DEPOSIT or WITHDRAWAL. Key = source account id. */
    public static final String TRANSACTIONS_COMPLETED = "bank.transactions.completed";

    /** One message per KYC status change. Key = customer id. */
    public static final String KYC_STATUS_CHANGED = "bank.kyc.status-changed";

    private Topics() {}
}
