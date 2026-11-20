package com.bankcore;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankcore.domain.Account;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP-level tests: authentication, role checks and the JSON error format. JWTs are simulated by Spring Security's test support. */
@AutoConfigureMockMvc
class ApiSecurityTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private static JwtRequestPostProcessor as(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("name", "Test " + subject))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
    }

    @Test
    @DisplayName("No token -> 401")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Swagger UI and health are public")
    void docsArePublic() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/v1/transfers']").exists());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A customer cannot call teller or compliance or admin endpoints -> 403")
    void roleChecks() throws Exception {
        TestUser user = approvedCustomer("100.00");
        mvc.perform(post("/api/v1/accounts/" + user.account().id() + "/deposit")
                        .with(as(user.caller().subject(), "customer"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10.00}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/kyc").with(as(user.caller().subject(), "customer")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/reconciliation").with(as(user.caller().subject(), "teller")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A teller can deposit; the response is 201 and a retry returns 200 + Idempotent-Replay")
    void tellerDepositAndReplay() throws Exception {
        TestUser user = approvedCustomer("0");
        String key = UUID.randomUUID().toString();
        String path = "/api/v1/accounts/" + user.account().id() + "/deposit";

        mvc.perform(post(path).with(as("teller-sub", "teller")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 75.00, \"description\": \"cash\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"));

        mvc.perform(post(path).with(as("teller-sub", "teller")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 75.00, \"description\": \"cash\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));

        Account reloaded = accounts.findById(user.account().id()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.balance()).isEqualByComparingTo(new BigDecimal("75.00"));
    }

    @Test
    @DisplayName("Missing Idempotency-Key -> 400, overdraft -> 422 with a stable error code")
    void errorFormat() throws Exception {
        TestUser sender = approvedCustomer("10.00");
        TestUser receiver = approvedCustomer("0");
        String body = "{\"sourceAccountNumber\":\"" + sender.account().accountNumber()
                + "\",\"destinationAccountNumber\":\"" + receiver.account().accountNumber() + "\",\"amount\":500.00}";

        mvc.perform(post("/api/v1/transfers").with(as(sender.caller().subject(), "customer"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/transfers").with(as(sender.caller().subject(), "customer"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("Validation errors list the bad fields")
    void validation() throws Exception {
        TestUser sender = approvedCustomer("10.00");
        mvc.perform(post("/api/v1/transfers").with(as(sender.caller().subject(), "customer"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountNumber\":\"\",\"destinationAccountNumber\":\"X\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.amount").exists());
    }

    @Test
    @DisplayName("Nobody can read another customer's account (404, not 403, so ids are not revealed)")
    void accountsArePrivate() throws Exception {
        TestUser owner = approvedCustomer("10.00");
        TestUser stranger = approvedCustomer("0");
        mvc.perform(get("/api/v1/accounts/" + owner.account().id()).with(as(stranger.caller().subject(), "customer")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts/" + owner.account().id()).with(as(owner.caller().subject(), "customer")))
                .andExpect(status().isOk());
    }
}
