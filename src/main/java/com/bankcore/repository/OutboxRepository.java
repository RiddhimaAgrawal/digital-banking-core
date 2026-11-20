package com.bankcore.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The transactional outbox: events waiting to be published to Kafka. */
@Repository
public class OutboxRepository {

    public record OutboxRow(long id, UUID eventId, String topic, String eventKey, String payload) {}

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, String topic, String eventKey, String eventType, String payloadJson) {
        jdbc.sql("""
                insert into outbox_events (event_id, topic, event_key, event_type, payload)
                values (:eid, :topic, :key, :type, cast(:payload as jsonb))
                """)
                .param("eid", eventId)
                .param("topic", topic)
                .param("key", eventKey)
                .param("type", eventType)
                .param("payload", payloadJson)
                .update();
    }

    /**
     * Claims up to {@code limit} unpublished events. {@code FOR UPDATE SKIP LOCKED} means that if you run
     * several instances of the app, each instance claims different rows and none publishes the same row twice.
     */
    public List<OutboxRow> lockUnpublished(int limit) {
        return jdbc.sql("""
                select id, event_id, topic, event_key, cast(payload as text) as payload
                from outbox_events
                where published_at is null
                order by id
                limit :limit
                for update skip locked
                """)
                .param("limit", limit)
                .query((rs, i) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("event_key"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(long id) {
        jdbc.sql("update outbox_events set published_at = now() where id = :id")
                .param("id", id).update();
    }

    public long countUnpublished() {
        Long n = jdbc.sql("select count(*) from outbox_events where published_at is null")
                .query(Long.class).single();
        return n == null ? 0 : n;
    }
}
