package com.bankcore.service;

import com.bankcore.domain.Account;
import com.bankcore.exception.BadRequestException;
import com.bankcore.repository.AccountRepository;
import com.bankcore.repository.StatementRepository;
import com.bankcore.repository.StatementRepository.Line;
import com.bankcore.repository.StatementRepository.Snapshot;
import com.bankcore.security.Caller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly account statements.
 *
 * <ul>
 *   <li>The LINES always come from the ledger (the source of truth).</li>
 *   <li>The summary (opening, credits, debits, closing) is computed from the ledger too, except for a CLOSED month that
 *       already has a stored snapshot, which is used as is. Snapshots are written by the monthly job.</li>
 *   <li>Months are calendar months in UTC.</li>
 * </ul>
 */
@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    /** A rendered statement. */
    public record Statement(
            UUID accountId,
            String accountNumber,
            String currency,
            String period,
            BigDecimal openingBalance,
            BigDecimal totalCredits,
            BigDecimal totalDebits,
            BigDecimal closingBalance,
            int entryCount,
            boolean periodClosed,
            List<Line> lines) {}

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final StatementRepository statements;

    public StatementService(AccountService accountService, AccountRepository accounts, StatementRepository statements) {
        this.accountService = accountService;
        this.accounts = accounts;
        this.statements = statements;
    }

    @Transactional(readOnly = true)
    public Statement statement(UUID accountId, YearMonth period, Caller caller) {
        Account account = accountService.getVisible(accountId, caller);
        return build(account, period);
    }

    /** Called by the monthly scheduler (and by an admin endpoint). Returns how many snapshots were newly written. */
    @Transactional
    public int generateSnapshotsForAll(YearMonth period) {
        requireClosed(period);
        int written = 0;
        for (Account account : accounts.findAllCustomerAccounts()) {
            Statement s = compute(account, period);
            Snapshot snapshot = new Snapshot(period.toString(), s.openingBalance(), s.closingBalance(),
                    s.totalCredits(), s.totalDebits(), s.entryCount());
            if (statements.insertSnapshotIfAbsent(account.id(), snapshot)) {
                written++;
            }
        }
        log.info("Statement snapshots for {}: {} newly written", period, written);
        return written;
    }

    // ---------------------------------------------------------------------------------------------

    private Statement build(Account account, YearMonth period) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (start(period).isAfter(now)) {
            throw new BadRequestException("Statement period " + period + " is in the future");
        }
        boolean closed = !end(period).isAfter(now);
        if (closed) {
            return statements.findSnapshot(account.id(), period.toString())
                    .map(snap -> fromSnapshot(account, period, snap))
                    .orElseGet(() -> compute(account, period));
        }
        return compute(account, period);
    }

    private Statement fromSnapshot(Account account, YearMonth period, Snapshot snap) {
        List<Line> lines = statements.lines(account.id(), start(period), end(period));
        return new Statement(account.id(), account.accountNumber(), account.currency(), period.toString(),
                snap.openingBalance(), snap.totalCredits(), snap.totalDebits(), snap.closingBalance(),
                snap.entryCount(), true, lines);
    }

    private Statement compute(Account account, YearMonth period) {
        OffsetDateTime start = start(period);
        OffsetDateTime end = end(period);
        BigDecimal opening = statements.balanceBefore(account.id(), start);
        List<Line> lines = statements.lines(account.id(), start, end);

        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal debits = BigDecimal.ZERO;
        for (Line line : lines) {
            if (line.amount().signum() > 0) {
                credits = credits.add(line.amount());
            } else {
                debits = debits.add(line.amount().negate());
            }
        }
        BigDecimal closing = opening.add(credits).subtract(debits);
        boolean closed = !end.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
        return new Statement(account.id(), account.accountNumber(), account.currency(), period.toString(),
                opening, credits, debits, closing, lines.size(), closed, lines);
    }

    private static void requireClosed(YearMonth period) {
        if (end(period).isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BadRequestException("Month " + period + " has not ended yet");
        }
    }

    private static OffsetDateTime start(YearMonth period) {
        return period.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime end(YearMonth period) {
        return period.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
