package com.bankcore.repository;

import com.bankcore.domain.Account;
import com.bankcore.domain.AccountStatus;
import com.bankcore.domain.AccountType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    /** Fixed id of the bank's cash clearing account, created by migration V4. */
    public static final UUID CASH_CLEARING_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static final RowMapper<Account> MAPPER = (rs, i) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            rs.getString("account_number"),
            AccountType.valueOf(rs.getString("type")),
            rs.getString("currency").trim(),
            rs.getBigDecimal("balance"),
            rs.getBoolean("allow_negative"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final String COLS =
            "id, customer_id, account_number, type, currency, balance, allow_negative, status, created_at";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(UUID customerId, AccountType type, String currency) {
        return jdbc.sql("""
                insert into accounts (customer_id, account_number, type, currency)
                values (:cid, cast(nextval('account_number_seq') as varchar), :type, :cur)
                returning\s""" + COLS)
                .param("cid", customerId)
                .param("type", type.name())
                .param("cur", currency)
                .query(MAPPER).single();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("select " + COLS + " from accounts where id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<Account> findByNumber(String accountNumber) {
        return jdbc.sql("select " + COLS + " from accounts where account_number = :n")
                .param("n", accountNumber).query(MAPPER).optional();
    }

    public List<Account> findByCustomer(UUID customerId) {
        return jdbc.sql("select " + COLS + " from accounts where customer_id = :cid order by created_at, id")
                .param("cid", customerId).query(MAPPER).list();
    }

    public List<Account> findAllCustomerAccounts() {
        return jdbc.sql("select " + COLS + " from accounts where type <> 'SYSTEM' order by id")
                .query(MAPPER).list();
    }

    /**
     * Locks BOTH accounts of a movement with {@code SELECT ... FOR NO KEY UPDATE}, always in ascending id
     * order. Because every transaction takes its locks in the same global order, two opposite transfers
     * (A to B and B to A at the same time) can never deadlock; the second one simply waits.
     *
     * <p>{@code NO KEY UPDATE} (instead of plain {@code UPDATE}) is deliberate: it still excludes every other
     * writer of the balance, but it does NOT block foreign-key checks (which take {@code KEY SHARE}). So other
     * transactions can still insert rows that merely reference the account (fraud alerts, ledger lines of a
     * waiting transfer) without being blocked by this lock for the wrong reason.
     *
     * <p>The returned rows are the committed, locked state: safe to read the balance from.
     */
    public List<Account> lockBoth(UUID idA, UUID idB) {
        return jdbc.sql("select " + COLS + " from accounts where id in (:a, :b) order by id for no key update")
                .param("a", idA)
                .param("b", idB)
                .query(MAPPER).list();
    }

    /** Adds {@code delta} (negative to debit) to the cached balance. The caller must hold the row lock. */
    public void applyDelta(UUID accountId, BigDecimal delta) {
        jdbc.sql("update accounts set balance = balance + :delta where id = :id")
                .param("delta", delta)
                .param("id", accountId)
                .update();
    }
}
