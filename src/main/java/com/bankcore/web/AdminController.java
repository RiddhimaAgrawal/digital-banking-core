package com.bankcore.web;

import com.bankcore.domain.FraudAlert;
import com.bankcore.domain.Notification;
import com.bankcore.repository.FraudRepository;
import com.bankcore.repository.NotificationRepository;
import com.bankcore.security.Caller;
import com.bankcore.service.CustomerService;
import com.bankcore.service.ReconciliationService;
import com.bankcore.service.ReconciliationService.Report;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Admin & monitoring", description = "Ledger reconciliation, fraud alerts, customer notifications")
public class AdminController {

    private final ReconciliationService reconciliation;
    private final FraudRepository fraudAlerts;
    private final NotificationRepository notifications;
    private final CustomerService customers;

    public AdminController(ReconciliationService reconciliation, FraudRepository fraudAlerts,
                           NotificationRepository notifications, CustomerService customers) {
        this.reconciliation = reconciliation;
        this.fraudAlerts = fraudAlerts;
        this.notifications = notifications;
        this.customers = customers;
    }

    @GetMapping("/admin/reconciliation")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Prove the books balance",
            description = "Checks that (1) the sum of ALL ledger entries is 0, (2) every transaction sums to 0, "
                    + "(3) every account's cached balance equals the sum of its ledger entries.")
    public Report reconcile() {
        return reconciliation.run();
    }

    @GetMapping("/admin/fraud-alerts")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN')")
    @Operation(summary = "Recent fraud alerts (blocked transfers), newest first")
    public List<FraudAlert> fraudAlerts(@RequestParam(defaultValue = "50") int limit) {
        return fraudAlerts.findRecent(Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/notifications")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "My notifications (created by the Kafka consumer), newest first")
    public List<Notification> myNotifications(@RequestParam(defaultValue = "50") int limit, JwtAuthenticationToken auth) {
        var customer = customers.currentCustomer(Caller.from(auth));
        return notifications.findByCustomer(customer.id(), Math.min(Math.max(limit, 1), 200));
    }
}
