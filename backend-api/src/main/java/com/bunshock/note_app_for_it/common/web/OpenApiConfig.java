package com.bunshock.note_app_for_it.common.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adds the "Authorize" (Bearer session token) button to Swagger UI — see IMPLEMENTED_ENDPOINTS.md. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "sessionToken";

    @Bean
    public OpenAPI middlewareOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Generador de Notas IT — Middleware (v1, no GLPI adapter)")
                        .description("Auto-generated from the live controllers — see "
                                + "IMPLEMENTED_ENDPOINTS.md for the design-vs-actual notes this "
                                + "spec doesn't carry (permission requirements, deviations from "
                                + "backend-contract.md, what's deferred vs. not started).")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque")
                                .description("The sessionToken returned by POST /api/v1/auth/login")));
    }
}
