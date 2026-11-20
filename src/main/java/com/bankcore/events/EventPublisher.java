package com.bankcore.events;

import com.bankcore.domain.Account;
import com.bankcore.domain.BankTransaction;
import com.bankcore.domain.KycStatus;
import com.bankcore.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records domain events in the OUTBOX table. This does NOT talk to Kafka. The row is inserted in the same
 * database transaction as the business change (propagation MANDATORY enforces that), so the event exists
 * if and only if the change committed. {@link OutboxPublisher} ships the rows to Kafka afterwards.
 */
@Component
public class EventPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public EventPublisher(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transactionCompleted(BankTransaction tx, Account source, Account destination) {
        UUID eventId = UUID.randomUUID();
        TransactionCompletedEvent event = new TransactionCompletedEvent(
                eventId,
                tx.type().name() + "_COMPLETED",
                tx.id(),
                tx.type().name(),
                tx.amount(),
                tx.currency(),
                source.id(),
                source.accountNumber(),
                source.customerId(),
                destination.id(),
                destination.accountNumber(),
                destination.customerId(),
                tx.description(),
                OffsetDateTime.now(ZoneOffset.UTC));
        outbox.insert(eventId, Topics.TRANSACTIONS_COMPLETED, source.id().toString(),
                event.eventType(), toJson(event));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void kycStatusChanged(UUID customerId, KycStatus status, String note) {
        UUID eventId = UUID.randomUUID();
        String type = switch (status) {
            case PENDING -> "KYC_SUBMITTED";
            case APPROVED -> "KYC_APPROVED";
            case REJECTED -> "KYC_REJECTED";
            case NOT_SUBMITTED -> "KYC_RESET";
        };
        KycStatusChangedEvent event = new KycStatusChangedEvent(
                eventId, type, customerId, status.name(), note, OffsetDateTime.now(ZoneOffset.UTC));
        outbox.insert(eventId, Topics.KYC_STATUS_CHANGED, customerId.toString(), type, toJson(event));
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event", e);
        }
    }
}
