package com.bankcore.web;

import com.bankcore.domain.KycSubmission;
import com.bankcore.security.Caller;
import com.bankcore.service.KycService;
import com.bankcore.web.dto.Requests.KycSubmitRequest;
import com.bankcore.web.dto.Requests.ReviewRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "KYC", description = "Know-your-customer workflow: submit, review, approve or reject")
public class KycController {

    private final KycService kyc;

    public KycController(KycService kyc) {
        this.kyc = kyc;
    }

    @PostMapping("/kyc")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Submit KYC documents for review")
    public ResponseEntity<KycSubmission> submit(@Valid @RequestBody KycSubmitRequest request, JwtAuthenticationToken auth) {
        KycSubmission s = kyc.submit(Caller.from(auth), request.documentType(), request.documentNumber(),
                request.dateOfBirth(), request.address());
        return ResponseEntity.created(URI.create("/api/v1/kyc/me")).body(s);
    }

    @GetMapping("/kyc/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "My latest KYC submission and its status")
    public KycSubmission mine(JwtAuthenticationToken auth) {
        return kyc.latestForCaller(Caller.from(auth));
    }

    @GetMapping("/admin/kyc")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN')")
    @Operation(summary = "Compliance: list KYC submissions by status (default PENDING)")
    public List<KycSubmission> list(@RequestParam(defaultValue = "PENDING") String status) {
        return kyc.listByStatus(status);
    }

    @PostMapping("/admin/kyc/{id}/approve")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN')")
    @Operation(summary = "Compliance: approve a pending KYC submission")
    public KycSubmission approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ReviewRequest body,
                                 JwtAuthenticationToken auth) {
        return kyc.approve(id, body == null ? null : body.note(), Caller.from(auth));
    }

    @PostMapping("/admin/kyc/{id}/reject")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN')")
    @Operation(summary = "Compliance: reject a pending KYC submission (a reason is required)")
    public KycSubmission reject(@PathVariable UUID id, @Valid @RequestBody(required = false) ReviewRequest body,
                                JwtAuthenticationToken auth) {
        return kyc.reject(id, body == null ? null : body.note(), Caller.from(auth));
    }
}
