package com.bankcore.repository;

import com.bankcore.domain.FraudAlert;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FraudRepository {

    static final RowMapper<FraudAlert> MAPPER = (rs, i) -> new FraudAlert(
            rs.getLong("id"),
            rs.getObject("customer_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("rule"),
            rs.getString("detail"),
            rs.getBigDecimal("amount"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public FraudRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID customerId, UUID accountId, String rule, String detail, BigDecimal amount) {
        jdbc.sql("""
                insert into fraud_alerts (customer_id, account_id, rule, detail, amount)
                values (:cid, :acc, :rule, :detail, :amount)
                """)
                .param("cid", customerId)
                .param("acc", accountId)
                .param("rule", rule)
                .param("detail", detail)
                .param("amount", amount)
                .update();
    }

    public List<FraudAlert> findRecent(int limit) {
        return jdbc.sql("select id, customer_id, account_id, rule, detail, amount, created_at "
                        + "from fraud_alerts order by id desc limit :limit")
                .param("limit", limit)
                .query(MAPPER).list();
    }
}
