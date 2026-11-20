package com.bankcore.events;

import com.bankcore.repository.NotificationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that turns events into customer notifications (stored in the notifications table; a real bank
 * would also send an SMS / push / email here). It is idempotent: the unique key (event_id, customer_id) makes a
 * redelivered event a no-op.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final ObjectMapper objectMapper;
    private final NotificationRepository notifications;

    public NotificationConsumer(ObjectMapper objectMapper, NotificationRepository notifications) {
        this.objectMapper = objectMapper;
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS_COMPLETED, groupId = "bank-notifications")
    public void onTransactionCompleted(String payload) throws JsonProcessingException {
        TransactionCompletedEvent e = objectMapper.readValue(payload, TransactionCompletedEvent.class);
        String detail = e.currency() + " " + e.amount().toPlainString();

        String debitMessage;
        String creditMessage;
        switch (e.transactionType()) {
            case "DEPOSIT" -> {
                debitMessage = null;
                creditMessage = detail + " deposited to account " + e.destinationAccountNumber();
            }
            case "WITHDRAWAL" -> {
                debitMessage = detail + " withdrawn from account " + e.sourceAccountNumber();
                creditMessage = null;
            }
            default -> {
                debitMessage = detail + " sent from account " + e.sourceAccountNumber()
                        + " to account " + e.destinationAccountNumber();
                creditMessage = detail + " received in account " + e.destinationAccountNumber()
                        + " from account " + e.sourceAccountNumber();
            }
        }

        if (e.sourceCustomerId() != null && debitMessage != null) {
            notify(e.sourceCustomerId(), e.eventId(), "DEBIT", debitMessage);
        }
        if (e.destinationCustomerId() != null && creditMessage != null) {
            notify(e.destinationCustomerId(), e.eventId(), "CREDIT", creditMessage);
        }
    }

    @KafkaListener(topics = Topics.KYC_STATUS_CHANGED, groupId = "bank-notifications")
    public void onKycStatusChanged(String payload) throws JsonProcessingException {
        KycStatusChangedEvent e = objectMapper.readValue(payload, KycStatusChangedEvent.class);
        String message = switch (e.eventType()) {
            case "KYC_SUBMITTED" -> "Your KYC documents were received and are waiting for review.";
            case "KYC_APPROVED" -> "Your KYC was approved. You can now open accounts and transfer money.";
            case "KYC_REJECTED" -> "Your KYC was rejected" + (e.note() == null ? "." : ": " + e.note());
            default -> "Your KYC status changed to " + e.status() + ".";
        };
        notify(e.customerId(), e.eventId(), e.eventType(), message);
    }

    private void notify(UUID customerId, UUID eventId, String kind, String message) {
        boolean created = notifications.insertIfAbsent(customerId, eventId, kind, message);
        if (created) {
            log.info("NOTIFY customer={} [{}] {}", customerId, kind, message);
        } else {
            log.debug("Duplicate event {} for customer {} ignored", eventId, customerId);
        }
    }
}
