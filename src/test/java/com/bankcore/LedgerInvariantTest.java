package com.bankcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bankcore.service.ReconciliationService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The database itself must refuse to let the books go out of balance, even if the Java code had a bug. */
class LedgerInvariantTest extends AbstractIntegrationTest {

    @Autowired PlatformTransactionManager txManager;
    @Autowired ReconciliationService reconciliation;

    @Test
    @DisplayName("An unbalanced pair of ledger entries is rejected at COMMIT by the database trigger")
    void unbalancedLedgerCannotCommit() {
        TestUser user = approvedCustomer("0");
        UUID txId = UUID.randomUUID();

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into transactions (id, type, source_account_id, destination_account_id, amount, currency, initiated_by)
                    values (:id, 'DEPOSIT', :src, :dst, 100, 'NPR', 'test')
                    """)
                    .param("id", txId)
                    .param("src", com.bankcore.repository.AccountRepository.CASH_CLEARING_ID)
                    .param("dst", user.account().id())
                    .update();
            // Only the credit side: the entries sum to +100, not 0.
            jdbc.sql("insert into ledger_entries (transaction_id, account_id, amount, balance_after) values (:t, :a, 100, 100)")
                    .param("t", txId).param("a", user.account().id()).update();
        })).hasMessageContaining("Unbalanced ledger");

        Long rows = jdbc.sql("select count(*) from ledger_entries where transaction_id = :t")
                .param("t", txId).query(Long.class).single();
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("Ledger entries are append-only: UPDATE and DELETE are refused")
    void ledgerIsAppendOnly() {
        TestUser user = approvedCustomer("100.00");

        assertThatThrownBy(() -> jdbc.sql("update ledger_entries set amount = amount + 1 where account_id = :a")
                .param("a", user.account().id()).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from ledger_entries where account_id = :a")
                .param("a", user.account().id()).update())
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("A customer account can never go below zero, even by direct SQL")
    void overdraftBlockedByConstraint() {
        TestUser user = approvedCustomer("10.00");
        assertThatThrownBy(() -> jdbc.sql("update accounts set balance = -1 where id = :a")
                .param("a", user.account().id()).update())
                .hasMessageContaining("balance_not_negative");
    }

    @Test
    @DisplayName("After deposits and transfers: ledger total is 0 and every cached balance equals its ledger sum")
    void booksBalanceAfterActivity() {
        TestUser a = approvedCustomer("250.00");
        TestUser b = approvedCustomer("75.50");
        transfers.transfer(UUID.randomUUID().toString(), a.account().accountNumber(),
                b.account().accountNumber(), new BigDecimal("100.25"), "t1", a.caller());
        transfers.withdraw(UUID.randomUUID().toString(), b.account().id(), new BigDecimal("20.00"), "cash", TELLER);

        var report = reconciliation.run();
        assertThat(report.healthy()).isTrue();
        assertThat(report.totalLedgerSum()).isEqualByComparingTo("0");
        assertThat(report.unbalancedTransactions()).isEmpty();
        assertThat(report.balanceMismatches()).isEmpty();
        assertThat(balanceOf(a.account())).isEqualByComparingTo("149.75");
        assertThat(balanceOf(b.account())).isEqualByComparingTo("155.75");
    }
}
