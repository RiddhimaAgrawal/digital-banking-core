package com.bankcore.web;

import com.bankcore.exception.BadRequestException;
import com.bankcore.repository.StatementRepository.Line;
import com.bankcore.security.Caller;
import com.bankcore.service.StatementService;
import com.bankcore.service.StatementService.Statement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Statements", description = "Monthly account statements (JSON and CSV)")
public class StatementController {

    private final StatementService statements;

    public StatementController(StatementService statements) {
        this.statements = statements;
    }

    @GetMapping("/accounts/{id}/statements/{period}")
    @PreAuthorize("hasAnyRole('CUSTOMER','TELLER','ADMIN')")
    @Operation(summary = "Monthly statement as JSON", description = "period format: yyyy-MM, for example 2026-10")
    public Statement statement(@PathVariable UUID id, @PathVariable String period, JwtAuthenticationToken auth) {
        return statements.statement(id, parse(period), Caller.from(auth));
    }

    @GetMapping(value = "/accounts/{id}/statements/{period}/csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('CUSTOMER','TELLER','ADMIN')")
    @Operation(summary = "Monthly statement as a CSV download")
    public ResponseEntity<String> statementCsv(@PathVariable UUID id, @PathVariable String period,
                                               JwtAuthenticationToken auth) {
        Statement s = statements.statement(id, parse(period), Caller.from(auth));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"statement-" + s.accountNumber() + "-" + s.period() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(toCsv(s));
    }

    @PostMapping("/admin/statements/generate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin: run the monthly statement job now for a CLOSED month",
            description = "Stores a snapshot (opening/closing balance, totals) for every customer account. Normally runs by itself on the 1st of each month.")
    public Map<String, Object> generate(@RequestParam String period) {
        YearMonth ym = parse(period);
        int written = statements.generateSnapshotsForAll(ym);
        return Map.of("period", ym.toString(), "snapshotsWritten", written);
    }

    private static YearMonth parse(String period) {
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("period must look like 2026-10 (yyyy-MM)");
        }
    }

    static String toCsv(Statement s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Account,").append(s.accountNumber()).append('\n');
        sb.append("Period,").append(s.period()).append('\n');
        sb.append("Currency,").append(s.currency()).append('\n');
        sb.append("Opening balance,").append(s.openingBalance().toPlainString()).append('\n');
        sb.append("Total credits,").append(s.totalCredits().toPlainString()).append('\n');
        sb.append("Total debits,").append(s.totalDebits().toPlainString()).append('\n');
        sb.append("Closing balance,").append(s.closingBalance().toPlainString()).append('\n');
        sb.append('\n');
        sb.append("Posted at,Transaction id,Type,Description,Amount,Balance after\n");
        for (Line l : s.lines()) {
            sb.append(l.postedAt()).append(',')
                    .append(l.transactionId()).append(',')
                    .append(l.transactionType()).append(',')
                    .append(csvEscape(l.description())).append(',')
                    .append(l.amount().toPlainString()).append(',')
                    .append(l.balanceAfter().toPlainString()).append('\n');
        }
        return sb.toString();
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        // Prevent spreadsheet formula injection (=, +, -, @) and escape quotes/commas.
        String safe = value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            safe = "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
