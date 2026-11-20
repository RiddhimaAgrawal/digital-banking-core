package com.bankcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bankcore.exception.FraudRuleViolationException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Fraud rules with small limits so they are easy to trigger. */
@TestPropertySource(properties = {
        "bank.fraud.max-transfer-amount=500.00",
        "bank.fraud.daily-limit=800.00",
        "bank.fraud.velocity-max-transfers=3",
        "bank.fraud.velocity-window-seconds=60"
})
class FraudRulesTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("A single transfer above the per-transfer limit is blocked, rolled back, and logged as an alert")
    void maxAmountRule() {
        TestUser sender = approvedCustomer("5000.00");
        TestUser receiver = approvedCustomer("0");

        assertThatThrownBy(() -> transfers.transfer(UUID.randomUUID().toString(), sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("501.00"), "big", sender.caller()))
                .isInstanceOfSatisfying(FraudRuleViolationException.class,
                        e -> assertThat(e.getRule()).isEqualTo("MAX_TRANSFER_AMOUNT"));

        assertThat(balanceOf(sender.account())).isEqualByComparingTo("5000.00");   // nothing moved
        assertThat(alertCount(sender, "MAX_TRANSFER_AMOUNT")).isEqualTo(1);        // evidence survived the rollback
    }

    @Test
    @DisplayName("Transfers that together exceed the daily limit are blocked")
    void dailyLimitRule() {
        TestUser sender = approvedCustomer("5000.00");
        TestUser receiver = approvedCustomer("0");

        transfers.transfer(UUID.randomUUID().toString(), sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("500.00"), "one", sender.caller());

        // 500 already sent today; another 400 would make 900 > 800
        assertThatThrownBy(() -> transfers.transfer(UUID.randomUUID().toString(), sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("400.00"), "two", sender.caller()))
                .isInstanceOfSatisfying(FraudRuleViolationException.class,
                        e -> assertThat(e.getRule()).isEqualTo("DAILY_LIMIT"));

        // but 300 fits (500 + 300 = 800, which is not above the limit)
        transfers.transfer(UUID.randomUUID().toString(), sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("300.00"), "three", sender.caller());
        assertThat(balanceOf(sender.account())).isEqualByComparingTo("4200.00");
    }

    @Test
    @DisplayName("More than 3 transfers inside 60 seconds trips the velocity rule")
    void velocityRule() {
        TestUser sender = approvedCustomer("1000.00");
        TestUser receiver = approvedCustomer("0");

        for (int i = 0; i < 3; i++) {
            transfers.transfer(UUID.randomUUID().toString(), sender.account().accountNumber(),
                    receiver.account().accountNumber(), new BigDecimal("1.00"), "small " + i, sender.caller());
        }
        assertThatThrownBy(() -> transfers.transfer(UUID.randomUUID().toString(), sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("1.00"), "fourth", sender.caller()))
                .isInstanceOfSatisfying(FraudRuleViolationException.class, e -> {
                    assertThat(e.getRule()).isEqualTo("VELOCITY");
                    assertThat(e.getStatus().value()).isEqualTo(429);
                });
        assertThat(balanceOf(sender.account())).isEqualByComparingTo("997.00");
    }

    @Test
    @DisplayName("Teller deposits are not subject to the customer transfer rules")
    void depositsAreNotThrottled() {
        TestUser user = approvedCustomer("0");
        for (int i = 0; i < 6; i++) {
            transfers.deposit(UUID.randomUUID().toString(), user.account().id(), new BigDecimal("1000.00"),
                    "cash " + i, TELLER);
        }
        assertThat(balanceOf(user.account())).isEqualByComparingTo("6000.00");
    }

    private long alertCount(TestUser user, String rule) {
        Long n = jdbc.sql("select count(*) from fraud_alerts where account_id = :a and rule = :r")
                .param("a", user.account().id()).param("r", rule).query(Long.class).single();
        return n == null ? 0 : n;
    }
}
