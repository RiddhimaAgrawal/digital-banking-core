package com.bankcore.web;

import com.bankcore.domain.Account;
import com.bankcore.domain.BankTransaction;
import com.bankcore.domain.Customer;
import com.bankcore.domain.LedgerEntry;
import com.bankcore.security.Caller;
import com.bankcore.service.AccountService;
import com.bankcore.service.CustomerService;
import com.bankcore.service.TransferResult;
import com.bankcore.service.TransferService;
import com.bankcore.web.dto.Requests.CashRequest;
import com.bankcore.web.dto.Requests.OpenAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Accounts", description = "Customer profile, accounts, ledger entries and teller cash operations")
public class AccountController {

    private final AccountService accounts;
    private final CustomerService customers;
    private final TransferService transfers;

    public AccountController(AccountService accounts, CustomerService customers, TransferService transfers) {
        this.accounts = accounts;
        this.customers = customers;
        this.transfers = transfers;
    }

    @GetMapping("/customers/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "My customer profile (created automatically on first call)")
    public Customer me(JwtAuthenticationToken auth) {
        return customers.currentCustomer(Caller.from(auth));
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Open a new account (requires approved KYC)")
    public ResponseEntity<Account> open(@Valid @RequestBody OpenAccountRequest request, JwtAuthenticationToken auth) {
        Account account = accounts.open(Caller.from(auth), request.type());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id())).body(account);
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "List my accounts")
    public List<Account> mine(JwtAuthenticationToken auth) {
        return accounts.listMine(Caller.from(auth));
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','TELLER','ADMIN')")
    @Operation(summary = "Get one account (owner, teller or admin)")
    public Account get(@PathVariable UUID id, JwtAuthenticationToken auth) {
        return accounts.getVisible(id, Caller.from(auth));
    }

    @GetMapping("/accounts/{id}/entries")
    @PreAuthorize("hasAnyRole('CUSTOMER','TELLER','ADMIN')")
    @Operation(summary = "Ledger entries of an account, newest first (negative amount = debit)")
    public List<LedgerEntry> entries(@PathVariable UUID id,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     JwtAuthenticationToken auth) {
        return accounts.entries(id, page, size, Caller.from(auth));
    }

    @PostMapping("/accounts/{id}/deposit")
    @PreAuthorize("hasAnyRole('TELLER','ADMIN')")
    @Operation(summary = "Teller: deposit cash into an account",
            description = "Idempotent: send a unique Idempotency-Key header. A retry with the same key and body returns the original result.")
    public ResponseEntity<BankTransaction> deposit(
            @PathVariable UUID id,
            @Parameter(description = "Unique key per logical operation, e.g. a UUID") @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CashRequest request,
            JwtAuthenticationToken auth) {
        TransferResult result = transfers.deposit(idempotencyKey, id, request.amount(), request.description(),
                Caller.from(auth));
        return respond(result);
    }

    @PostMapping("/accounts/{id}/withdraw")
    @PreAuthorize("hasAnyRole('TELLER','ADMIN')")
    @Operation(summary = "Teller: withdraw cash from an account",
            description = "Idempotent: send a unique Idempotency-Key header.")
    public ResponseEntity<BankTransaction> withdraw(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CashRequest request,
            JwtAuthenticationToken auth) {
        TransferResult result = transfers.withdraw(idempotencyKey, id, request.amount(), request.description(),
                Caller.from(auth));
        return respond(result);
    }

    /** 201 for a newly booked transaction, 200 + Idempotent-Replay header for a replayed one. */
    static ResponseEntity<BankTransaction> respond(TransferResult result) {
        BankTransaction tx = result.transaction();
        if (result.replayed()) {
            return ResponseEntity.status(HttpStatus.OK).header("Idempotent-Replay", "true").body(tx);
        }
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + tx.id())).body(tx);
    }
}
