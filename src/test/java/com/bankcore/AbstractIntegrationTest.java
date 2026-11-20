package com.bankcore;

import com.bankcore.domain.Account;
import com.bankcore.domain.AccountType;
import com.bankcore.domain.Customer;
import com.bankcore.repository.AccountRepository;
import com.bankcore.repository.CustomerRepository;
import com.bankcore.security.Caller;
import com.bankcore.service.TransferService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class of the integration tests. It starts ONE real PostgreSQL 16 in Docker (Testcontainers) shared by all
 * test classes, lets Flyway build the schema, and turns off Kafka publishing so no broker is needed.
 *
 * <p>Test settings live in src/test/resources/application-test.yml. The fraud limits are very high there so the
 * concurrency tests are not throttled; FraudRulesTest overrides them.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected JdbcClient jdbc;
    @Autowired protected CustomerRepository customers;
    @Autowired protected AccountRepository accounts;
    @Autowired protected TransferService transfers;

    protected static final Caller TELLER =
            new Caller("teller-sub", "Tina Teller", "tina@bank.local", Set.of(Caller.TELLER));

    /** A customer with one checking account. */
    protected record TestUser(Caller caller, Customer customer, Account account) {}

    protected TestUser newCustomer(String kycStatus, BigDecimal openingBalance) {
        String sub = "sub-" + UUID.randomUUID();
        jdbc.sql("insert into customers (keycloak_user_id, full_name, email, kyc_status) values (:s, :n, :e, :k)")
                .param("s", sub).param("n", "Test " + sub).param("e", sub + "@test.local").param("k", kycStatus)
                .update();
        Customer customer = customers.findByKeycloakUserId(sub).orElseThrow();
        Account account = accounts.insert(customer.id(), AccountType.CHECKING, "NPR");
        Caller caller = new Caller(sub, customer.fullName(), customer.email(), Set.of(Caller.CUSTOMER));
        if (openingBalance.signum() > 0) {
            transfers.deposit("seed-" + UUID.randomUUID(), account.id(), openingBalance, "opening balance", TELLER);
            account = accounts.findById(account.id()).orElseThrow();
        }
        return new TestUser(caller, customer, account);
    }

    protected TestUser approvedCustomer(String openingBalance) {
        return newCustomer("APPROVED", new BigDecimal(openingBalance));
    }

    protected BigDecimal balanceOf(Account account) {
        return accounts.findById(account.id()).orElseThrow().balance();
    }

    protected BigDecimal ledgerSumOf(Account account) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where account_id = :a")
                .param("a", account.id()).query(BigDecimal.class).single();
    }

    /** Outcome of one parallel task: either a value or the exception it threw. */
    protected record Outcome<T>(T value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }

    /**
     * Runs {@code count} tasks at the same instant (all threads wait on a start gate), so they genuinely compete
     * for the same database rows, and returns every outcome.
     */
    protected static <T> List<Outcome<T>> parallel(int count, IntFunction<Callable<T>> taskFactory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Callable<T> task = taskFactory.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return new Outcome<>(task.call(), null);
                } catch (Throwable t) {
                    return new Outcome<>(null, t);
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Outcome<T>> outcomes = new ArrayList<>();
        for (Future<Outcome<T>> f : futures) {
            outcomes.add(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }
}
