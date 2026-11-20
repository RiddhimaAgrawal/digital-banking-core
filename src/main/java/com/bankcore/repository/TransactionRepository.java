package com.bankcore.repository;

import com.bankcore.domain.BankTransaction;
import com.bankcore.domain.LedgerEntry;
import com.bankcore.domain.TransactionType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Transactions, ledger entries and idempotency keys: everything on the money path. */
@Repository
public class TransactionRepository {

    /** What we remember about an Idempotency-Key that has already been used. */
    public record IdempotencyRecord(String requestHash, UUID transactionId) {}

    static final RowMapper<BankTransaction> TX_MAPPER = (rs, i) -> new BankTransaction(
            rs.getObject("id", UUID.class),
            TransactionType.valueOf(rs.getString("type")),
            rs.getString("status"),
            rs.getObject("source_account_id", UUID.class),
            rs.getObject("destination_account_id", UUID.class),
            rs.getBigDecimal("amount"),
            rs.getString("currency").trim(),
            rs.getString("description"),
            rs.getString("initiated_by"),
            rs.getObject("created_at", OffsetDateTime.class));

    static final RowMapper<LedgerEntry> ENTRY_MAPPER = (rs, i) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getObject("transaction_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getBigDecimal("amount"),
            rs.getBigDecimal("balance_after"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final String TX_COLS = "id, type, status, source_account_id, destination_account_id, amount, "
            + "currency, description, initiated_by, created_at";

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- transactions -------------------------------------------------------------------------

    public BankTransaction insertTransaction(TransactionType type, UUID sourceId, UUID destinationId,
                                             BigDecimal amount, String currency, String description,
                                             String initiatedBy) {
        return jdbc.sql("""
                insert into transactions (type, source_account_id, destination_account_id, amount, currency,
                                          description, initiated_by)
                values (:type, :src, :dst, :amount, :cur, :descr, :by)
                returning\s""" + TX_COLS)
                .param("type", type.name())
                .param("src", sourceId)
                .param("dst", destinationId)
                .param("amount", amount)
                .param("cur", currency)
                .param("descr", description)
                .param("by", initiatedBy)
                .query(TX_MAPPER).single();
    }

    public Optional<BankTransaction> findById(UUID id) {
        return jdbc.sql("select " + TX_COLS + " from transactions where id = :id")
                .param("id", id).query(TX_MAPPER).optional();
    }

    // ---- ledger entries -----------------------------------------------------------------------

    public void insertEntry(UUID transactionId, UUID accountId, BigDecimal signedAmount, BigDecimal balanceAfter) {
        jdbc.sql("""
                insert into ledger_entries (transaction_id, account_id, amount, balance_after)
                values (:tx, :acc, :amount, :after)
                """)
                .param("tx", transactionId)
                .param("acc", accountId)
                .param("amount", signedAmount)
                .param("after", balanceAfter)
                .update();
    }

    /** Newest first. */
    public List<LedgerEntry> findEntries(UUID accountId, int limit, int offset) {
        return jdbc.sql("""
                select id, transaction_id, account_id, amount, balance_after, created_at
                from ledger_entries where account_id = :acc
                order by id desc limit :limit offset :offset
                """)
                .param("acc", accountId)
                .param("limit", limit)
                .param("offset", offset)
                .query(ENTRY_MAPPER).list();
    }

    // ---- fraud-rule lookups (call these only while holding the source account lock) -----------

    /** Sum of completed outgoing TRANSFERs of this account since 00:00 UTC today. */
    public BigDecimal sumTransfersToday(UUID sourceAccountId) {
        return jdbc.sql("""
                select coalesce(sum(amount), 0) from transactions
                where source_account_id = :acc and type = 'TRANSFER' and status = 'COMPLETED'
                  and created_at >= (date_trunc('day', clock_timestamp() at time zone 'UTC') at time zone 'UTC')
                """)
                .param("acc", sourceAccountId)
                .query(BigDecimal.class).single();
    }

    /** Number of outgoing TRANSFERs of this account in the last {@code seconds} seconds. */
    public int countTransfersInLastSeconds(UUID sourceAccountId, int seconds) {
        Integer n = jdbc.sql("""
                select count(*) from transactions
                where source_account_id = :acc and type = 'TRANSFER'
                  and created_at > clock_timestamp() - (:secs * interval '1 second')
                """)
                .param("acc", sourceAccountId)
                .param("secs", seconds)
                .query(Integer.class).single();
        return n == null ? 0 : n;
    }

    // ---- idempotency keys ---------------------------------------------------------------------

    /**
     * Tries to reserve the key for this caller. Returns true if WE reserved it (first time), false if the
     * key already exists. If another transaction is inserting the same key right now, PostgreSQL makes
     * this statement wait until that transaction commits or rolls back, which is exactly what makes
     * concurrent duplicate requests safe.
     */
    public boolean tryClaimKey(String caller, String key, String requestHash) {
        int inserted = jdbc.sql("""
                insert into idempotency_keys (caller, idem_key, request_hash)
                values (:caller, :key, :hash)
                on conflict (caller, idem_key) do nothing
                """)
                .param("caller", caller)
                .param("key", key)
                .param("hash", requestHash)
                .update();
        return inserted == 1;
    }

    public Optional<IdempotencyRecord> findKey(String caller, String key) {
        return jdbc.sql("select request_hash, transaction_id from idempotency_keys where caller = :caller and idem_key = :key")
                .param("caller", caller)
                .param("key", key)
                .query((rs, i) -> new IdempotencyRecord(rs.getString("request_hash").trim(),
                        rs.getObject("transaction_id", UUID.class)))
                .optional();
    }

    public void completeKey(String caller, String key, UUID transactionId) {
        jdbc.sql("update idempotency_keys set transaction_id = :tx where caller = :caller and idem_key = :key")
                .param("tx", transactionId)
                .param("caller", caller)
                .param("key", key)
                .update();
    }
}
