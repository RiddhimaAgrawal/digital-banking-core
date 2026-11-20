package com.bankcore.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Integrity queries that prove the books balance. */
@Repository
public class ReconciliationRepository {

    public record BalanceMismatch(UUID accountId, String accountNumber, BigDecimal cachedBalance, BigDecimal ledgerSum) {}

    public record UnbalancedTransaction(UUID transactionId, BigDecimal sum) {}

    private final JdbcClient jdbc;

    public ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Sum of EVERY ledger entry in the bank. Must always be exactly zero. */
    public BigDecimal totalLedgerSum() {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries")
                .query(BigDecimal.class).single();
    }

    /** Transactions whose entries do not sum to zero. Must always be empty. */
    public List<UnbalancedTransaction> unbalancedTransactions() {
        return jdbc.sql("""
                select transaction_id, sum(amount) as total
                from ledger_entries group by transaction_id having sum(amount) <> 0
                """)
                .query((rs, i) -> new UnbalancedTransaction(
                        rs.getObject("transaction_id", UUID.class), rs.getBigDecimal("total")))
                .list();
    }

    /** Accounts whose cached balance differs from the sum of their ledger entries. Must always be empty. */
    public List<BalanceMismatch> balanceMismatches() {
        return jdbc.sql("""
                select a.id, a.account_number, a.balance, coalesce(sum(l.amount), 0) as ledger_sum
                from accounts a left join ledger_entries l on l.account_id = a.id
                group by a.id, a.account_number, a.balance
                having a.balance <> coalesce(sum(l.amount), 0)
                """)
                .query((rs, i) -> new BalanceMismatch(
                        rs.getObject("id", UUID.class),
                        rs.getString("account_number"),
                        rs.getBigDecimal("balance"),
                        rs.getBigDecimal("ledger_sum")))
                .list();
    }
}
