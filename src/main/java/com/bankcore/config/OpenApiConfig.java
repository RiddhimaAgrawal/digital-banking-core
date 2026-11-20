package com.bankcore.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 metadata. Swagger UI: /swagger-ui.html, raw spec: /v3/api-docs (JSON) and /v3/api-docs.yaml. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bankOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Digital Banking Core API")
                        .version("1.0.0")
                        .description("""
                                Accounts, atomic double-entry transfers with idempotency keys, KYC workflow, \
                                fraud rules, monthly statements and Kafka events.

                                Authenticate with a Keycloak access token: click Authorize and paste the token \
                                (without the word Bearer). Get tokens with scripts/get-token.sh."""))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
