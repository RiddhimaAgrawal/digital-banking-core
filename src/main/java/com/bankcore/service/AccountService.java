package com.bankcore.service;

import com.bankcore.config.BankProperties;
import com.bankcore.domain.Account;
import com.bankcore.domain.AccountType;
import com.bankcore.domain.Customer;
import com.bankcore.domain.LedgerEntry;
import com.bankcore.exception.BadRequestException;
import com.bankcore.exception.KycRequiredException;
import com.bankcore.exception.NotFoundException;
import com.bankcore.repository.AccountRepository;
import com.bankcore.repository.CustomerRepository;
import com.bankcore.repository.TransactionRepository;
import com.bankcore.security.Caller;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final TransactionRepository transactions;
    private final CustomerService customerService;
    private final BankProperties props;

    public AccountService(AccountRepository accounts, CustomerRepository customers,
                          TransactionRepository transactions, CustomerService customerService,
                          BankProperties props) {
        this.accounts = accounts;
        this.customers = customers;
        this.transactions = transactions;
        this.customerService = customerService;
        this.props = props;
    }

    /** Opens a new account. Only customers whose KYC is APPROVED may do so. */
    @Transactional
    public Account open(Caller caller, AccountType type) {
        if (type == AccountType.SYSTEM) {
            throw new BadRequestException("SYSTEM accounts cannot be opened through the API");
        }
        Customer customer = customerService.currentCustomer(caller);
        if (!customer.kycApproved()) {
            throw new KycRequiredException(
                    "KYC must be approved before opening an account (current status: " + customer.kycStatus() + ")");
        }
        return accounts.insert(customer.id(), type, props.currency());
    }

    @Transactional(readOnly = true)
    public List<Account> listMine(Caller caller) {
        Customer customer = customerService.currentCustomer(caller);
        return accounts.findByCustomer(customer.id());
    }

    /**
     * Loads an account the caller is allowed to see: the owner, or bank staff (teller/admin). For anyone else
     * the answer is "not found", so the API does not reveal which account ids exist.
     */
    @Transactional(readOnly = true)
    public Account getVisible(UUID accountId, Caller caller) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (!canView(account, caller)) {
            throw new NotFoundException("Account not found");
        }
        return account;
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entries(UUID accountId, int page, int size, Caller caller) {
        getVisible(accountId, caller);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        return transactions.findEntries(accountId, safeSize, safePage * safeSize);
    }

    boolean canView(Account account, Caller caller) {
        if (caller.isStaff()) {
            return true;
        }
        return customers.findByKeycloakUserId(caller.subject())
                .map(c -> c.id().equals(account.customerId()))
                .orElse(false);
    }
}
