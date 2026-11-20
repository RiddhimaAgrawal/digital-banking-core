package com.bankcore.service;

import com.bankcore.domain.Account;
import com.bankcore.domain.BankTransaction;
import com.bankcore.domain.TransactionType;
import com.bankcore.exception.BadRequestException;
import com.bankcore.exception.BusinessRuleException;
import com.bankcore.exception.InsufficientFundsException;
import com.bankcore.repository.AccountRepository;
import com.bankcore.repository.TransactionRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The double-entry engine. Everything that moves money goes through {@link #post}.
 *
 * <p>One call writes exactly one transaction row and TWO ledger entries:
 * <pre>
 *   debit  account:  -amount      (money leaves)
 *   credit account:  +amount      (money arrives)
 *                    -------
 *                     0          &lt;- always; also enforced by a database trigger at COMMIT
 * </pre>
 */
@Service
public class LedgerService {

    private final TransactionRepository transactions;
    private final AccountRepository accounts;

    public LedgerService(TransactionRepository transactions, AccountRepository accounts) {
        this.transactions = transactions;
        this.accounts = accounts;
    }

    /**
     * @param debit  the account that loses money. MUST be the row-locked, freshly-read copy
     * @param credit the account that gains money. MUST be the row-locked, freshly-read copy
     *
     * <p>{@code Propagation.MANDATORY}: refuses to run outside a transaction, so ledger entries can never be
     * written half-way.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BankTransaction post(TransactionType type, Account debit, Account credit, BigDecimal amount,
                                String description, String initiatedBy) {
        if (amount.signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero");
        }
        if (!debit.isActive() || !credit.isActive()) {
            throw new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Both accounts must be ACTIVE");
        }
        if (!debit.currency().equals(credit.currency())) {
            throw new BusinessRuleException("CURRENCY_MISMATCH",
                    "Accounts have different currencies (" + debit.currency() + " / " + credit.currency() + ")");
        }
        if (!debit.allowNegative() && debit.balance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient funds: balance " + debit.balance().toPlainString()
                            + ", requested " + amount.toPlainString());
        }

        BigDecimal debitBalanceAfter = debit.balance().subtract(amount);
        BigDecimal creditBalanceAfter = credit.balance().add(amount);

        BankTransaction tx = transactions.insertTransaction(
                type, debit.id(), credit.id(), amount, debit.currency(), description, initiatedBy);

        transactions.insertEntry(tx.id(), debit.id(), amount.negate(), debitBalanceAfter);
        transactions.insertEntry(tx.id(), credit.id(), amount, creditBalanceAfter);

        accounts.applyDelta(debit.id(), amount.negate());
        accounts.applyDelta(credit.id(), amount);
        return tx;
    }
}
