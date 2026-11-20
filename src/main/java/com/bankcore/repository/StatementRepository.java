package com.bankcore.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read queries that build monthly statements from the ledger, plus the stored month-end snapshots. */
@Repository
public class StatementRepository {

    /** Stored summary of a closed month. */
    public record Snapshot(String period, BigDecimal openingBalance, BigDecimal closingBalance,
                           BigDecimal totalCredits, BigDecimal totalDebits, int entryCount) {}

    /** One statement line: a ledger entry joined with its transaction. */
    public record Line(long entryId, OffsetDateTime postedAt, UUID transactionId, String transactionType,
                       String description, BigDecimal amount, BigDecimal balanceAfter) {}

    private final JdbcClient jdbc;

    public StatementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Balance at the start of the period = sum of every entry posted before it. */
    public BigDecimal balanceBefore(UUID accountId, OffsetDateTime periodStart) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where account_id = :acc and created_at < :start")
                .param("acc", accountId)
                .param("start", periodStart)
                .query(BigDecimal.class).single();
    }

    public List<Line> lines(UUID accountId, OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        return jdbc.sql("""
                select l.id, l.created_at, l.transaction_id, t.type, t.description, l.amount, l.balance_after
                from ledger_entries l join transactions t on t.id = l.transaction_id
                where l.account_id = :acc and l.created_at >= :start and l.created_at < :end
                order by l.id
                """)
                .param("acc", accountId)
                .param("start", periodStart)
                .param("end", periodEnd)
                .query((rs, i) -> new Line(
                        rs.getLong("id"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getString("type"),
                        rs.getString("description"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after")))
                .list();
    }

    public Optional<Snapshot> findSnapshot(UUID accountId, String period) {
        return jdbc.sql("""
                select period, opening_balance, closing_balance, total_credits, total_debits, entry_count
                from account_statements where account_id = :acc and period = :period
                """)
                .param("acc", accountId)
                .param("period", period)
                .query((rs, i) -> new Snapshot(
                        rs.getString("period").trim(),
                        rs.getBigDecimal("opening_balance"),
                        rs.getBigDecimal("closing_balance"),
                        rs.getBigDecimal("total_credits"),
                        rs.getBigDecimal("total_debits"),
                        rs.getInt("entry_count")))
                .optional();
    }

    /** @return true if a new snapshot row was written, false if one already existed. */
    public boolean insertSnapshotIfAbsent(UUID accountId, Snapshot s) {
        int n = jdbc.sql("""
                insert into account_statements (account_id, period, opening_balance, closing_balance,
                                                total_credits, total_debits, entry_count)
                values (:acc, :period, :open, :close, :credits, :debits, :count)
                on conflict (account_id, period) do nothing
                """)
                .param("acc", accountId)
                .param("period", s.period())
                .param("open", s.openingBalance())
                .param("close", s.closingBalance())
                .param("credits", s.totalCredits())
                .param("debits", s.totalDebits())
                .param("count", s.entryCount())
                .update();
        return n == 1;
    }
}
