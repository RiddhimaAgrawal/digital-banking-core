package com.bankcore.repository;

import com.bankcore.domain.Customer;
import com.bankcore.domain.KycStatus;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {

    static final RowMapper<Customer> MAPPER = (rs, i) -> new Customer(
            rs.getObject("id", UUID.class),
            rs.getString("keycloak_user_id"),
            rs.getString("full_name"),
            rs.getString("email"),
            KycStatus.valueOf(rs.getString("kyc_status")),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final String COLS = "id, keycloak_user_id, full_name, email, kyc_status, created_at";

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> findById(UUID id) {
        return jdbc.sql("select " + COLS + " from customers where id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<Customer> findByKeycloakUserId(String keycloakUserId) {
        return jdbc.sql("select " + COLS + " from customers where keycloak_user_id = :sub")
                .param("sub", keycloakUserId).query(MAPPER).optional();
    }

    /** Creates the customer if the Keycloak user has none yet. Safe under concurrent first requests. */
    public void insertIfAbsent(String keycloakUserId, String fullName, String email) {
        jdbc.sql("""
                insert into customers (keycloak_user_id, full_name, email)
                values (:sub, :name, :email)
                on conflict (keycloak_user_id) do nothing
                """)
                .param("sub", keycloakUserId)
                .param("name", fullName)
                .param("email", email)
                .update();
    }

    public void updateKycStatus(UUID customerId, KycStatus status) {
        jdbc.sql("update customers set kyc_status = :status where id = :id")
                .param("status", status.name())
                .param("id", customerId)
                .update();
    }
}
