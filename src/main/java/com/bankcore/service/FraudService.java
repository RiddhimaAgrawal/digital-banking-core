package com.bankcore.service;

import com.bankcore.config.BankProperties;
import com.bankcore.domain.Account;
import com.bankcore.domain.Customer;
import com.bankcore.exception.FraudRuleViolationException;
import com.bankcore.repository.TransactionRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * Simple, explainable fraud rules applied to outgoing customer TRANSFERs:
 * <ol>
 *   <li><b>MAX_TRANSFER_AMOUNT</b>: one transfer may not exceed the per-transfer limit.</li>
 *   <li><b>DAILY_LIMIT</b>: outgoing transfers since 00:00 UTC plus this one may not exceed the daily limit.</li>
 *   <li><b>VELOCITY</b>: no more than N transfers from the same account within a sliding window of S seconds.</li>
 * </ol>
 * Call this only AFTER the source account row is locked: concurrent transfers from the same account are then
 * serialised, so two parallel requests cannot both slip under a limit that they would break together.
 *
 * <p>A violation throws {@link FraudRuleViolationException}. The alert row is saved by {@link TransferService}
 * after the transaction has rolled back (so no second database connection is needed while one is held).
 */
@Service
public class FraudService {

    private final BankProperties props;
    private final TransactionRepository transactions;

    public FraudService(BankProperties props, TransactionRepository transactions) {
        this.props = props;
        this.transactions = transactions;
    }

    public void check(Customer customer, Account source, BigDecimal amount) {
        BankProperties.Fraud rules = props.fraud();

        if (amount.compareTo(rules.maxTransferAmount()) > 0) {
            throw violation(customer, source, amount, "MAX_TRANSFER_AMOUNT",
                    "Transfer of " + amount.toPlainString() + " exceeds the per-transfer limit of "
                            + rules.maxTransferAmount().toPlainString());
        }

        BigDecimal sentToday = transactions.sumTransfersToday(source.id());
        if (sentToday.add(amount).compareTo(rules.dailyLimit()) > 0) {
            throw violation(customer, source, amount, "DAILY_LIMIT",
                    "Already sent " + sentToday.toPlainString() + " today; adding " + amount.toPlainString()
                            + " would exceed the daily limit of " + rules.dailyLimit().toPlainString());
        }

        int recent = transactions.countTransfersInLastSeconds(source.id(), rules.velocityWindowSeconds());
        if (recent >= rules.velocityMaxTransfers()) {
            throw violation(customer, source, amount, "VELOCITY",
                    recent + " transfers in the last " + rules.velocityWindowSeconds()
                            + " seconds (limit " + rules.velocityMaxTransfers() + ")");
        }
    }

    private static FraudRuleViolationException violation(Customer customer, Account source, BigDecimal amount,
                                                         String rule, String detail) {
        return new FraudRuleViolationException(rule, detail, customer.id(), source.id(), amount);
    }
}
