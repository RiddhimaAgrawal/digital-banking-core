package com.bankcore.service;

import com.bankcore.repository.ReconciliationRepository;
import com.bankcore.repository.ReconciliationRepository.BalanceMismatch;
import com.bankcore.repository.ReconciliationRepository.UnbalancedTransaction;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Proves the books balance. All three checks must pass at all times. */
@Service
public class ReconciliationService {

    public record Report(
            boolean healthy,
            BigDecimal totalLedgerSum,
            List<UnbalancedTransaction> unbalancedTransactions,
            List<BalanceMismatch> balanceMismatches) {}

    private final ReconciliationRepository repository;

    public ReconciliationService(ReconciliationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Report run() {
        BigDecimal total = repository.totalLedgerSum();
        List<UnbalancedTransaction> unbalanced = repository.unbalancedTransactions();
        List<BalanceMismatch> mismatches = repository.balanceMismatches();
        boolean healthy = total.signum() == 0 && unbalanced.isEmpty() && mismatches.isEmpty();
        return new Report(healthy, total, unbalanced, mismatches);
    }
}
