package com.bankcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bankcore.domain.AccountType;
import com.bankcore.domain.DocumentType;
import com.bankcore.domain.KycStatus;
import com.bankcore.domain.KycSubmission;
import com.bankcore.exception.BusinessRuleException;
import com.bankcore.exception.ConflictException;
import com.bankcore.exception.ForbiddenException;
import com.bankcore.exception.KycRequiredException;
import com.bankcore.security.Caller;
import com.bankcore.service.AccountService;
import com.bankcore.service.KycService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class KycWorkflowTest extends AbstractIntegrationTest {

    @Autowired KycService kyc;
    @Autowired AccountService accountService;

    private static final Caller COMPLIANCE =
            new Caller("compliance-sub", "Carl Compliance", "carl@bank.local", Set.of(Caller.COMPLIANCE));

    private TestUser unverified() {
        return newCustomer("NOT_SUBMITTED", BigDecimal.ZERO);
    }

    private KycSubmission submit(TestUser user) {
        return kyc.submit(user.caller(), DocumentType.PASSPORT, "P" + UUID.randomUUID().toString().substring(0, 8),
                LocalDate.of(1995, 4, 12), "Kathmandu, Nepal");
    }

    @Test
    @DisplayName("Submit -> approve: the customer becomes APPROVED and can open an account")
    void happyPath() {
        TestUser user = unverified();

        assertThatThrownBy(() -> accountService.open(user.caller(), AccountType.SAVINGS))
                .isInstanceOf(KycRequiredException.class);

        KycSubmission submission = submit(user);
        assertThat(submission.status()).isEqualTo("PENDING");
        assertThat(customers.findById(user.customer().id()).orElseThrow().kycStatus()).isEqualTo(KycStatus.PENDING);

        KycSubmission approved = kyc.approve(submission.id(), "documents verified", COMPLIANCE);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.reviewer()).isEqualTo("compliance-sub");
        assertThat(customers.findById(user.customer().id()).orElseThrow().kycStatus()).isEqualTo(KycStatus.APPROVED);

        assertThat(accountService.open(user.caller(), AccountType.SAVINGS).customerId())
                .isEqualTo(user.customer().id());
    }

    @Test
    @DisplayName("A customer who is not KYC-approved cannot transfer money")
    void unverifiedCustomerCannotTransfer() {
        TestUser sender = newCustomer("PENDING", BigDecimal.ZERO);
        TestUser receiver = approvedCustomer("0");
        transfers.deposit(UUID.randomUUID().toString(), sender.account().id(), new BigDecimal("100.00"), "x", TELLER);

        assertThatThrownBy(() -> transfers.transfer(UUID.randomUUID().toString(), sender.account().accountNumber(),
                receiver.account().accountNumber(), new BigDecimal("10.00"), "nope", sender.caller()))
                .isInstanceOf(KycRequiredException.class);
    }

    @Test
    @DisplayName("Rejection requires a reason; a rejected customer can resubmit")
    void rejectAndResubmit() {
        TestUser user = unverified();
        KycSubmission first = submit(user);

        assertThatThrownBy(() -> kyc.reject(first.id(), " ", COMPLIANCE))
                .hasMessageContaining("reason");

        KycSubmission rejected = kyc.reject(first.id(), "document unreadable", COMPLIANCE);
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(customers.findById(user.customer().id()).orElseThrow().kycStatus()).isEqualTo(KycStatus.REJECTED);

        KycSubmission second = submit(user);                       // allowed again after a rejection
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Only one pending submission per customer, and a decision can be made only once")
    void stateRules() {
        TestUser user = unverified();
        KycSubmission s = submit(user);

        assertThatThrownBy(() -> submit(user)).isInstanceOf(ConflictException.class);

        kyc.approve(s.id(), null, COMPLIANCE);
        assertThatThrownBy(() -> kyc.approve(s.id(), null, COMPLIANCE)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> kyc.reject(s.id(), "late", COMPLIANCE)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> submit(user)).isInstanceOf(ConflictException.class);   // already approved
    }

    @Test
    @DisplayName("Four-eyes rule: a reviewer cannot approve their own KYC")
    void selfReviewForbidden() {
        TestUser user = unverified();
        KycSubmission s = submit(user);
        Caller sameUserAsReviewer = new Caller(user.caller().subject(), "x", "x@x", Set.of(Caller.COMPLIANCE));

        assertThatThrownBy(() -> kyc.approve(s.id(), null, sameUserAsReviewer))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("Applicants under 18 are rejected up front")
    void underage() {
        TestUser user = unverified();
        assertThatThrownBy(() -> kyc.submit(user.caller(), DocumentType.NATIONAL_ID, "N1",
                LocalDate.now().minusYears(15), "Pokhara"))
                .isInstanceOf(BusinessRuleException.class);
    }
}
