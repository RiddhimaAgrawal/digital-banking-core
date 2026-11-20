package com.bankcore.service;

import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** On the 1st of every month (01:00 UTC) store a snapshot of last month's statement for every account. */
@Component
public class StatementScheduler {

    private final StatementService statements;

    public StatementScheduler(StatementService statements) {
        this.statements = statements;
    }

    @Scheduled(cron = "${bank.statements.cron}", zone = "UTC")
    public void generateLastMonth() {
        statements.generateSnapshotsForAll(YearMonth.now(ZoneOffset.UTC).minusMonths(1));
    }
}
