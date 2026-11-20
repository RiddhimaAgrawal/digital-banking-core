package com.bankcore.events;

import com.bankcore.config.BankProperties;
import com.bankcore.repository.OutboxRepository;
import com.bankcore.repository.OutboxRepository.OutboxRow;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background job that moves events from the outbox table to Kafka.
 *
 * <p>Every {@code bank.outbox.poll-interval-ms} it claims a batch of unpublished rows, sends each one and
 * waits for the broker acknowledgement, then marks it published. If Kafka is down it stops, leaves the rows
 * unpublished and tries again on the next tick, so no event is lost. If the app crashes after a send but before
 * the mark, the event is sent again later (at-least-once delivery); consumers de-duplicate on {@code eventId}.
 */
@Component
@ConditionalOnProperty(prefix = "bank.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final BankProperties props;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka, BankProperties props) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${bank.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxRow> batch = outbox.lockUnpublished(props.outbox().batchSize());
        for (OutboxRow row : batch) {
            try {
                kafka.send(row.topic(), row.eventKey(), row.payload()).get(10, TimeUnit.SECONDS);
                outbox.markPublished(row.id());
                log.debug("Published outbox event {} to {}", row.eventId(), row.topic());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                // Kafka unavailable: keep this and all later rows for the next tick (preserves order).
                log.warn("Could not publish outbox event {} ({}); will retry", row.eventId(), e.toString());
                return;
            }
        }
    }
}
