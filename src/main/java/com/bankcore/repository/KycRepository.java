package com.bankcore.repository;

import com.bankcore.domain.DocumentType;
import com.bankcore.domain.KycSubmission;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KycRepository {

    static final RowMapper<KycSubmission> MAPPER = (rs, i) -> new KycSubmission(
            rs.getObject("id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            DocumentType.valueOf(rs.getString("document_type")),
            rs.getString("document_number"),
            rs.getObject("date_of_birth", LocalDate.class),
            rs.getString("address"),
            rs.getString("status"),
            rs.getString("reviewer"),
            rs.getString("review_note"),
            rs.getObject("submitted_at", OffsetDateTime.class),
            rs.getObject("reviewed_at", OffsetDateTime.class));

    private static final String COLS = "id, customer_id, document_type, document_number, date_of_birth, address, "
            + "status, reviewer, review_note, submitted_at, reviewed_at";

    private final JdbcClient jdbc;

    public KycRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KycSubmission insert(UUID customerId, DocumentType type, String documentNumber, LocalDate dob, String address) {
        return jdbc.sql("""
                insert into kyc_submissions (customer_id, document_type, document_number, date_of_birth, address)
                values (:cid, :type, :num, :dob, :addr)
                returning\s""" + COLS)
                .param("cid", customerId)
                .param("type", type.name())
                .param("num", documentNumber)
                .param("dob", dob)
                .param("addr", address)
                .query(MAPPER).single();
    }

    /** Row-locks the submission so two reviewers cannot decide the same one at the same time. */
    public Optional<KycSubmission> findByIdForUpdate(UUID id) {
        return jdbc.sql("select " + COLS + " from kyc_submissions where id = :id for update")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<KycSubmission> findLatestByCustomer(UUID customerId) {
        return jdbc.sql("select " + COLS + " from kyc_submissions where customer_id = :cid order by submitted_at desc limit 1")
                .param("cid", customerId).query(MAPPER).optional();
    }

    public List<KycSubmission> findByStatus(String status) {
        return jdbc.sql("select " + COLS + " from kyc_submissions where status = :status order by submitted_at")
                .param("status", status).query(MAPPER).list();
    }

    public void markReviewed(UUID id, String newStatus, String reviewer, String note) {
        jdbc.sql("""
                update kyc_submissions
                set status = :status, reviewer = :reviewer, review_note = :note, reviewed_at = now()
                where id = :id
                """)
                .param("status", newStatus)
                .param("reviewer", reviewer)
                .param("note", note)
                .param("id", id)
                .update();
    }
}
