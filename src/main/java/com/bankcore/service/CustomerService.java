package com.bankcore.service;

import com.bankcore.domain.Customer;
import com.bankcore.repository.CustomerRepository;
import com.bankcore.security.Caller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    /**
     * Returns the customer record for the authenticated Keycloak user, creating it on first use
     * ("just-in-time provisioning"), so a brand-new user can call the API without a separate sign-up step.
     */
    @Transactional
    public Customer currentCustomer(Caller caller) {
        return customers.findByKeycloakUserId(caller.subject()).orElseGet(() -> {
            customers.insertIfAbsent(caller.subject(), caller.name(), caller.email());
            return customers.findByKeycloakUserId(caller.subject())
                    .orElseThrow(() -> new IllegalStateException("Customer provisioning failed"));
        });
    }
}
