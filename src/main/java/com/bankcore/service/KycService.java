package com.bankcore.service;

import com.bankcore.domain.Customer;
import com.bankcore.domain.DocumentType;
import com.bankcore.domain.KycStatus;
import com.bankcore.domain.KycSubmission;
import com.bankcore.events.EventPublisher;
import com.bankcore.exception.BadRequestException;
import com.bankcore.exception.BusinessRuleException;
import com.bankcore.exception.ConflictException;
import com.bankcore.exception.ForbiddenException;
import com.bankcore.exception.NotFoundException;
import com.bankcore.repository.CustomerRepository;
import com.bankcore.repository.KycRepository;
import com.bankcore.security.Caller;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KYC (Know Your Customer) workflow:
 * <pre>
 *   NOT_SUBMITTED --submit--> PENDING --approve--> APPROVED
 *                                 \--reject---> REJECTED --submit again--> PENDING
 * </pre>
 * Customers submit; compliance officers (or admins) decide. Until a customer is APPROVED they cannot open
 * accounts or transfer money.
 */
@Service
public class KycService {

    private static final String PENDING = "PENDING";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";

    private final KycRepository kyc;
    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final EventPublisher events;

    public KycService(KycRepository kyc, CustomerRepository customers, CustomerService customerService,
                      EventPublisher events) {
        this.kyc = kyc;
        this.customers = customers;
        this.customerService = customerService;
        this.events = events;
    }

    @Transactional
    public KycSubmission submit(Caller caller, DocumentType documentType, String documentNumber,
                                LocalDate dateOfBirth, String address) {
        Customer customer = customerService.currentCustomer(caller);
        if (customer.kycStatus() == KycStatus.APPROVED) {
            throw new ConflictException("KYC is already approved");
        }
        if (customer.kycStatus() == KycStatus.PENDING) {
            throw new ConflictException("A KYC submission is already waiting for review");
        }
        int age = Period.between(dateOfBirth, LocalDate.now(ZoneOffset.UTC)).getYears();
        if (age < 18) {
            throw new BusinessRuleException("UNDERAGE", "Customers must be at least 18 years old");
        }

        KycSubmission submission = kyc.insert(customer.id(), documentType, documentNumber, dateOfBirth, address);
        customers.updateKycStatus(customer.id(), KycStatus.PENDING);
        events.kycStatusChanged(customer.id(), KycStatus.PENDING, null);
        return submission;
    }

    @Transactional(readOnly = true)
    public KycSubmission latestForCaller(Caller caller) {
        Customer customer = customerService.currentCustomer(caller);
        return kyc.findLatestByCustomer(customer.id())
                .orElseThrow(() -> new NotFoundException("You have not submitted KYC documents yet"));
    }

    @Transactional(readOnly = true)
    public List<KycSubmission> listByStatus(String status) {
        String normalised = status == null ? PENDING : status.toUpperCase();
        if (!List.of(PENDING, APPROVED, REJECTED).contains(normalised)) {
            throw new BadRequestException("status must be PENDING, APPROVED or REJECTED");
        }
        return kyc.findByStatus(normalised);
    }

    @Transactional
    public KycSubmission approve(UUID submissionId, String note, Caller reviewer) {
        return review(submissionId, true, note, reviewer);
    }

    @Transactional
    public KycSubmission reject(UUID submissionId, String note, Caller reviewer) {
        if (note == null || note.isBlank()) {
            throw new BadRequestException("A rejection reason is required");
        }
        return review(submissionId, false, note, reviewer);
    }

    private KycSubmission review(UUID submissionId, boolean approve, String note, Caller reviewer) {
        // Row lock: two reviewers cannot decide the same submission at the same time.
        KycSubmission submission = kyc.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new NotFoundException("KYC submission not found"));
        if (!PENDING.equals(submission.status())) {
            throw new ConflictException("This submission was already " + submission.status());
        }
        Customer customer = customers.findById(submission.customerId())
                .orElseThrow(() -> new IllegalStateException("Customer of KYC submission is missing"));
        // Four-eyes principle: nobody reviews their own KYC.
        if (customer.keycloakUserId().equals(reviewer.subject())) {
            throw new ForbiddenException("You cannot review your own KYC submission");
        }

        String newStatus = approve ? APPROVED : REJECTED;
        KycStatus customerStatus = approve ? KycStatus.APPROVED : KycStatus.REJECTED;
        kyc.markReviewed(submissionId, newStatus, reviewer.subject(), note);
        customers.updateKycStatus(customer.id(), customerStatus);
        events.kycStatusChanged(customer.id(), customerStatus, note);
        return kyc.findByIdForUpdate(submissionId).orElseThrow();
    }
}
