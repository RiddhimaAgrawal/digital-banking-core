package com.bankcore.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authenticated user making a request, extracted from the Keycloak JWT.
 *
 * @param subject the JWT "sub" claim (Keycloak user id)
 * @param name    display name (claim "name", else "preferred_username")
 * @param email   claim "email"
 * @param roles   realm roles in lower case, e.g. "customer", "teller", "compliance", "admin"
 */
public record Caller(String subject, String name, String email, Set<String> roles) {

    public static final String CUSTOMER = "customer";
    public static final String TELLER = "teller";
    public static final String COMPLIANCE = "compliance";
    public static final String ADMIN = "admin";

    public static Caller from(JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) {
            name = jwt.getClaimAsString("preferred_username");
        }
        if (name == null || name.isBlank()) {
            name = jwt.getSubject();
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            email = jwt.getSubject() + "@unknown.local";
        }
        // Authorities look like "ROLE_CUSTOMER" (see SecurityConfig); strip the prefix.
        Set<String> roles = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()).toLowerCase())
                .collect(Collectors.toSet());
        return new Caller(jwt.getSubject(), name, email, roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** Bank employees who may look at any customer's account. */
    public boolean isStaff() {
        return hasRole(TELLER) || hasRole(ADMIN);
    }
}
