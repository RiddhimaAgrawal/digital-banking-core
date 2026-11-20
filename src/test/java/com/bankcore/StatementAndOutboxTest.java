package com.bankcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bankcore.exception.InsufficientFundsException;
import com.bankcore.repository.OutboxRepository;
import com.bankcore.service.StatementService;
import com.bankcore.service.StatementService.Statement;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StatementAndOutboxTest extends AbstractIntegrationTest {

    @Autowired StatementService statements;
    @Autowired OutboxRepository outbox;

    @Test
    @DisplayName("Current-month statement: opening + credits - debits = closing = account balance")
    void statementAddsUp() {
        TestUser a = approvedCustomer("1000.00");
        TestUser b = approvedCustomer("0");
        transfers.transfer(UUID.randomUUID().toString(), a.account().accountNumber(),
                b.account().accountNumber(), new BigDecimal("250.50"), "rent", a.caller());
        transfers.withdraw(UUID.randomUUID().toString(), a.account().id(), new BigDecimal("49.50"), "atm", TELLER);

        Statement s = statements.statement(a.account().id(), YearMonth.now(ZoneOffset.UTC), a.caller());

        assertThat(s.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.totalCredits()).isEqualByComparingTo("1000.00");
        assertThat(s.totalDebits()).isEqualByComparingTo("300.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("700.00");
        assertThat(s.closingBalance()).isEqualByComparingTo(balanceOf(a.account()));
        assertThat(s.entryCount()).isEqualTo(3);
        assertThat(s.lines()).hasSize(3);
        assertThat(s.periodClosed()).isFalse();
    }

    @Test
    @DisplayName("A statement for an earlier month is empty with opening = closing = 0 for a new account")
    void emptyPastMonth() {
        TestUser a = approvedCustomer("100.00");
        Statement s = statements.statement(a.account().id(),
                YearMonth.now(ZoneOffset.UTC).minusMonths(3), a.caller());
        assertThat(s.entryCount()).isZero();
        assertThat(s.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.periodClosed()).isTrue();
    }

    @Test
    @DisplayName("Another customer cannot read my statement (answer is 'not found')")
    void statementIsPrivate() {
        TestUser a = approvedCustomer("100.00");
        TestUser stranger = approvedCustomer("0");
        assertThatThrownBy(() -> statements.statement(a.account().id(), YearMonth.now(ZoneOffset.UTC), stranger.caller()))
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("A committed transfer writes exactly one outbox event; a rolled-back one writes none")
    void outboxIsTransactional() {
        TestUser a = approvedCustomer("100.00");
        TestUser b = approvedCustomer("0");
        long before = outbox.countUnpublished();

        transfers.transfer(UUID.randomUUID().toString(), a.account().accountNumber(),
                b.account().accountNumber(), new BigDecimal("10.00"), "ok", a.caller());
        assertThat(outbox.countUnpublished()).isEqualTo(before + 1);

        assertThatThrownBy(() -> transfers.transfer(UUID.randomUUID().toString(), a.account().accountNumber(),
                b.account().accountNumber(), new BigDecimal("5000.00"), "fails", a.caller()))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(outbox.countUnpublished()).isEqualTo(before + 1);     // unchanged: the event was rolled back too

        String payload = jdbc.sql("select cast(payload as text) from outbox_events order by id desc limit 1")
                .query(String.class).single();
        // PostgreSQL's jsonb text output puts a space after the colon: "amount": 10.00
        assertThat(payload).contains("TRANSFER_COMPLETED").containsPattern("\"amount\":\\s*10\\.00");
    }
}
