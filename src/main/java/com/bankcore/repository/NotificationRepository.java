package com.bankcore.repository;

import com.bankcore.domain.Notification;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    static final RowMapper<Notification> MAPPER = (rs, i) -> new Notification(
            rs.getLong("id"),
            rs.getObject("customer_id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getString("kind"),
            rs.getString("message"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: a redelivered Kafka event inserts nothing because (event_id, customer_id) is unique. */
    public boolean insertIfAbsent(UUID customerId, UUID eventId, String kind, String message) {
        int n = jdbc.sql("""
                insert into notifications (customer_id, event_id, kind, message)
                values (:cid, :eid, :kind, :msg)
                on conflict (event_id, customer_id) do nothing
                """)
                .param("cid", customerId)
                .param("eid", eventId)
                .param("kind", kind)
                .param("msg", message)
                .update();
        return n == 1;
    }

    public List<Notification> findByCustomer(UUID customerId, int limit) {
        return jdbc.sql("""
                select id, customer_id, event_id, kind, message, created_at
                from notifications where customer_id = :cid order by id desc limit :limit
                """)
                .param("cid", customerId)
                .param("limit", limit)
                .query(MAPPER).list();
    }
}
