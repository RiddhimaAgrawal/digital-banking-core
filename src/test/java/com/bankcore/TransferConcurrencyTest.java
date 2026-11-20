package com.bankcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bankcore.exception.IdempotencyConflictException;
import com.bankcore.exception.InsufficientFundsException;
import com.bankcore.service.ReconciliationService;
import com.bankcore.service.TransferResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The tests that matter most: money must never be created, lost, or moved twice, however requests interleave. */
class TransferConcurrencyTest extends AbstractIntegrationTest {

    @Autowired ReconciliationService reconciliation;

    @Test
    @DisplayName("30 parallel transfers of 100 from an account holding 1000: exactly 10 succeed, never overdrawn")
    void parallelTransfersCannotOverdraw() throws Exception {
        TestUser sender = approvedCustomer("1000.00");
        TestUser receiver = approvedCustomer("0");

        List<Outcome<TransferResult>> outcomes = parallel(30, i -> () -> transfers.transfer(
                UUID.randomUUID().toString(), sender.account().accountNumber(), receiver.account().accountNumber(),
                new BigDecimal("100.00"), "race " + i, sender.caller()));

        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();
        long insufficient = outcomes.stream()
                .filter(o -> o.error() instanceof InsufficientFundsException).count();

        assertThat(succeeded).isEqualTo(10);
        assertThat(insufficient).isEqualTo(20);
        assertThat(balanceOf(sender.account())).isEqualByComparingTo("0.00");
        assertThat(balanceOf(receiver.account())).isEqualByComparingTo("1000.00");
        assertThat(ledgerSumOf(sender.account())).isEqualByComparingTo("0.00");
        assertThat(reconciliation.run().healthy()).isTrue();
    }

    @Test
    @DisplayName("20 parallel requests with the SAME Idempotency-Key move the money exactly once")
    void duplicateIdempotencyKeyChargesOnce() throws Exception {
        TestUser sender = approvedCustomer("500.00");
        TestUser receiver = approvedCustomer("0");
        String key = "retry-storm-" + UUID.randomUUID();

        List<Outcome<TransferResult>> outcomes = parallel(20, i -> () -> transfers.transfer(
                key, sender.account().accountNumber(), receiver.account().accountNumber(),
                new BigDecimal("200.00"), "rent", sender.caller()));

        // Every request must succeed (one real, the rest replays) and all must point at the same transaction.
        assertThat(outcomes).allMatch(Outcome::succeeded);
        long fresh = outcomes.stream().filter(o -> !o.value().replayed()).count();
        long replays = outcomes.stream().filter(o -> o.value().replayed()).count();
        long distinctTransactions = outcomes.stream().map(o -> o.value().transaction().id()).distinct().count();

        assertThat(fresh).isEqualTo(1);
        assertThat(replays).isEqualTo(19);
        assertThat(distinctTransactions).isEqualTo(1);
        assertThat(balanceOf(sender.account())).isEqualByComparingTo("300.00");   // debited ONCE
        assertThat(balanceOf(receiver.account())).isEqualByComparingTo("200.00");
        assertThat(reconciliation.run().healthy()).isTrue();
    }

    @Test
    @DisplayName("A sequential retry with the same key returns the original transaction and books nothing new")
    void sequentialRetryIsReplayed() {
        TestUser sender = approvedCustomer("500.00");
        TestUser receiver = approvedCustomer("0");
        String key = UUID.randomUUID().toString();

        TransferResult first = transfers.transfer(key, sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("50.00"), "gift", sender.caller());
        TransferResult retry = transfers.transfer(key, sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("50.00"), "gift", sender.caller());

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.transaction().id()).isEqualTo(first.transaction().id());
        assertThat(balanceOf(sender.account())).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("Reusing a key with a different amount is rejected, not silently accepted")
    void sameKeyDifferentBodyIsRejected() {
        TestUser sender = approvedCustomer("500.00");
        TestUser receiver = approvedCustomer("0");
        String key = UUID.randomUUID().toString();

        transfers.transfer(key, sender.account().accountNumber(), receiver.account().accountNumber(),
                new BigDecimal("50.00"), "gift", sender.caller());

        assertThatThrownBy(() -> transfers.transfer(key, sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("75.00"), "gift", sender.caller()))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(balanceOf(sender.account())).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("A failed transfer is rolled back completely and its key can be reused")
    void failedTransferLeavesNothingBehind() {
        TestUser sender = approvedCustomer("10.00");
        TestUser receiver = approvedCustomer("0");
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> transfers.transfer(key, sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("999.00"), "too much", sender.caller()))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(sender.account())).isEqualByComparingTo("10.00");
        Long keyRows = jdbc.sql("select count(*) from idempotency_keys where idem_key = :k")
                .param("k", key).query(Long.class).single();
        assertThat(keyRows).isZero();      // rolled back with the transfer, so the client may retry with this key

        TransferResult retry = transfers.transfer(key, sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("5.00"), "smaller", sender.caller());
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    @DisplayName("Opposite transfers A->B and B->A at the same time never deadlock and conserve money")
    void oppositeTransfersDoNotDeadlock() throws Exception {
        TestUser a = approvedCustomer("1000.00");
        TestUser b = approvedCustomer("1000.00");

        List<Outcome<TransferResult>> outcomes = parallel(40, i -> () -> {
            boolean aToB = i % 2 == 0;
            TestUser from = aToB ? a : b;
            TestUser to = aToB ? b : a;
            return transfers.transfer(UUID.randomUUID().toString(), from.account().accountNumber(),
                    to.account().accountNumber(), new BigDecimal("10.00"), "ping-pong " + i, from.caller());
        });

        assertThat(outcomes).allMatch(Outcome::succeeded);      // a deadlock would surface as an exception
        BigDecimal total = balanceOf(a.account()).add(balanceOf(b.account()));
        assertThat(total).isEqualByComparingTo("2000.00");
        assertThat(balanceOf(a.account())).isEqualByComparingTo("1000.00");   // 20 out, 20 in
        assertThat(reconciliation.run().healthy()).isTrue();
    }

    @Test
    @DisplayName("Many senders paying one receiver: the receiver ends with exactly the sum")
    void fanInIsExact() throws Exception {
        TestUser receiver = approvedCustomer("0");
        List<TestUser> senders = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            senders.add(approvedCustomer("100.00"));
        }

        List<Outcome<TransferResult>> outcomes = parallel(25, i -> () -> transfers.transfer(
                UUID.randomUUID().toString(), senders.get(i).account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("40.00"), "fan-in", senders.get(i).caller()));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(balanceOf(receiver.account())).isEqualByComparingTo("1000.00");
        assertThat(ledgerSumOf(receiver.account())).isEqualByComparingTo("1000.00");
        assertThat(reconciliation.run().healthy()).isTrue();
    }
}
