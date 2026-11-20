package com.bankcore.service;

import com.bankcore.domain.Account;
import com.bankcore.domain.AccountType;
import com.bankcore.domain.BankTransaction;
import com.bankcore.domain.Customer;
import com.bankcore.domain.TransactionType;
import com.bankcore.events.EventPublisher;
import com.bankcore.exception.BadRequestException;
import com.bankcore.exception.ConflictException;
import com.bankcore.exception.ForbiddenException;
import com.bankcore.exception.IdempotencyConflictException;
import com.bankcore.exception.KycRequiredException;
import com.bankcore.exception.NotFoundException;
import com.bankcore.repository.AccountRepository;
import com.bankcore.repository.CustomerRepository;
import com.bankcore.repository.TransactionRepository;
import com.bankcore.repository.TransactionRepository.IdempotencyRecord;
import com.bankcore.security.Caller;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves money. Each public method is ONE database transaction, so either everything below happens or nothing does:
 *
 * <ol>
 *   <li>validate the request and normalise the amount to 2 decimals</li>
 *   <li><b>idempotency</b>: reserve the Idempotency-Key; if it was used before, return the original result</li>
 *   <li>authorise (does the caller own the source account? is KYC approved?)</li>
 *   <li><b>lock</b> both account rows in a fixed order (no deadlocks, no lost updates)</li>
 *   <li>fraud rules (transfers only)</li>
 *   <li>post the double-entry ledger lines and update balances</li>
 *   <li>write the Kafka event to the outbox table</li>
 *   <li>remember which transaction the idempotency key produced</li>
 * </ol>
 * Any exception rolls back all of it, including the idempotency key, so a failed request can be retried.
 */
@Service
public class TransferTransactions {

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final TransactionRepository transactions;
    private final LedgerService ledger;
    private final FraudService fraud;
    private final EventPublisher events;

    public TransferTransactions(AccountRepository accounts, CustomerRepository customers,
                           TransactionRepository transactions, LedgerService ledger,
                           FraudService fraud, EventPublisher events) {
        this.accounts = accounts;
        this.customers = customers;
        this.transactions = transactions;
        this.ledger = ledger;
        this.fraud = fraud;
        this.events = events;
    }

    // ------------------------------------------------------------------------------------------
    // Customer-to-customer transfer
    // ------------------------------------------------------------------------------------------

    @Transactional
    public TransferResult transfer(String idempotencyKey, String sourceNumber, String destinationNumber,
                                   BigDecimal rawAmount, String description, Caller caller) {
        requireKey(idempotencyKey);
        BigDecimal amount = normalise(rawAmount);
        if (sourceNumber.equals(destinationNumber)) {
            throw new BadRequestException("Source and destination accounts must be different");
        }
        String hash = hash("TRANSFER", sourceNumber, destinationNumber, amount.toPlainString(), nullToEmpty(description));

        // 1. Idempotency: have we already done exactly this?
        Optional<BankTransaction> replay = replayIfDuplicate(caller.subject(), idempotencyKey, hash);
        if (replay.isPresent()) {
            return new TransferResult(replay.get(), true);
        }

        // 2. Authorisation (reads only, no locks yet)
        Customer customer = customers.findByKeycloakUserId(caller.subject())
                .orElseThrow(() -> new ForbiddenException("No customer profile exists for this user"));
        Account sourcePreview = accounts.findByNumber(sourceNumber)
                .orElseThrow(() -> new NotFoundException("Source account not found"));
        Account destinationPreview = accounts.findByNumber(destinationNumber)
                .orElseThrow(() -> new NotFoundException("Destination account not found"));
        if (!customer.id().equals(sourcePreview.customerId())) {
            throw new ForbiddenException("You do not own the source account");
        }
        if (destinationPreview.type() == AccountType.SYSTEM) {
            throw new BadRequestException("Cannot transfer to a bank system account");
        }
        if (!customer.kycApproved()) {
            throw new KycRequiredException(
                    "KYC must be approved before transferring money (current status: " + customer.kycStatus() + ")");
        }

        // 3. Lock both rows, then work only with the locked copies
        Map<UUID, Account> locked = lockBoth(sourcePreview.id(), destinationPreview.id());
        Account source = locked.get(sourcePreview.id());
        Account destination = locked.get(destinationPreview.id());

        // 4. Fraud rules (serialised per source account because we hold its lock)
        fraud.check(customer, source, amount);

        // 5. Double-entry posting, 6. outbox event, 7. remember the key's result
        BankTransaction tx = ledger.post(TransactionType.TRANSFER, source, destination, amount,
                description, caller.subject());
        events.transactionCompleted(tx, source, destination);
        transactions.completeKey(caller.subject(), idempotencyKey, tx.id());
        return new TransferResult(tx, false);
    }

    // ------------------------------------------------------------------------------------------
    // Teller cash operations (counter-party is the bank's cash clearing account)
    // ------------------------------------------------------------------------------------------

    @Transactional
    public TransferResult deposit(String idempotencyKey, UUID accountId, BigDecimal rawAmount,
                                  String description, Caller caller) {
        requireKey(idempotencyKey);
        BigDecimal amount = normalise(rawAmount);
        String hash = hash("DEPOSIT", accountId.toString(), amount.toPlainString(), nullToEmpty(description));

        Optional<BankTransaction> replay = replayIfDuplicate(caller.subject(), idempotencyKey, hash);
        if (replay.isPresent()) {
            return new TransferResult(replay.get(), true);
        }

        Account preview = requireCustomerAccount(accountId);
        Map<UUID, Account> locked = lockBoth(AccountRepository.CASH_CLEARING_ID, preview.id());
        Account clearing = locked.get(AccountRepository.CASH_CLEARING_ID);
        Account target = locked.get(preview.id());

        BankTransaction tx = ledger.post(TransactionType.DEPOSIT, clearing, target, amount,
                description, caller.subject());
        events.transactionCompleted(tx, clearing, target);
        transactions.completeKey(caller.subject(), idempotencyKey, tx.id());
        return new TransferResult(tx, false);
    }

    @Transactional
    public TransferResult withdraw(String idempotencyKey, UUID accountId, BigDecimal rawAmount,
                                   String description, Caller caller) {
        requireKey(idempotencyKey);
        BigDecimal amount = normalise(rawAmount);
        String hash = hash("WITHDRAWAL", accountId.toString(), amount.toPlainString(), nullToEmpty(description));

        Optional<BankTransaction> replay = replayIfDuplicate(caller.subject(), idempotencyKey, hash);
        if (replay.isPresent()) {
            return new TransferResult(replay.get(), true);
        }

        Account preview = requireCustomerAccount(accountId);
        Map<UUID, Account> locked = lockBoth(AccountRepository.CASH_CLEARING_ID, preview.id());
        Account clearing = locked.get(AccountRepository.CASH_CLEARING_ID);
        Account source = locked.get(preview.id());

        BankTransaction tx = ledger.post(TransactionType.WITHDRAWAL, source, clearing, amount,
                description, caller.subject());
        events.transactionCompleted(tx, source, clearing);
        transactions.completeKey(caller.subject(), idempotencyKey, tx.id());
        return new TransferResult(tx, false);
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    /** A transaction is visible to staff and to the owners of the two accounts it touched. */
    @Transactional(readOnly = true)
    public BankTransaction getVisible(UUID transactionId, Caller caller) {
        BankTransaction tx = transactions.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction not found"));
        if (caller.isStaff()) {
            return tx;
        }
        Optional<Customer> customer = customers.findByKeycloakUserId(caller.subject());
        boolean participant = customer.isPresent()
                && (ownedBy(tx.sourceAccountId(), customer.get()) || ownedBy(tx.destinationAccountId(), customer.get()));
        if (!participant) {
            throw new NotFoundException("Transaction not found");
        }
        return tx;
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private boolean ownedBy(UUID accountId, Customer customer) {
        return accounts.findById(accountId).map(a -> customer.id().equals(a.customerId())).orElse(false);
    }

    private Account requireCustomerAccount(UUID accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (account.type() == AccountType.SYSTEM) {
            throw new BadRequestException("Cannot post cash operations to a system account");
        }
        return account;
    }

    /** Locks the two rows and returns them keyed by id. Missing rows mean a bad id. */
    private Map<UUID, Account> lockBoth(UUID idA, UUID idB) {
        List<Account> rows = accounts.lockBoth(idA, idB);
        if (rows.size() != 2) {
            throw new NotFoundException("Account not found");
        }
        Map<UUID, Account> byId = new HashMap<>();
        for (Account a : rows) {
            byId.put(a.id(), a);
        }
        return byId;
    }

    /**
     * Reserves the idempotency key. Returns the original transaction if this exact request was already
     * processed; returns empty if the key is new and we should go ahead.
     */
    private Optional<BankTransaction> replayIfDuplicate(String caller, String key, String requestHash) {
        if (transactions.tryClaimKey(caller, key, requestHash)) {
            return Optional.empty();
        }
        IdempotencyRecord existing = transactions.findKey(caller, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key disappeared"));
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "This Idempotency-Key was already used with a different request; use a new key");
        }
        if (existing.transactionId() == null) {
            throw new ConflictException("A request with this Idempotency-Key is still being processed");
        }
        return transactions.findById(existing.transactionId());
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BadRequestException("Idempotency-Key header is required (1 to 128 characters)");
        }
    }

    private static BigDecimal normalise(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero");
        }
        if (amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Amount may have at most 2 decimal places");
        }
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
