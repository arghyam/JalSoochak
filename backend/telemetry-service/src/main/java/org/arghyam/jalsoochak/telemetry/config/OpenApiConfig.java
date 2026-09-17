package org.arghyam.jalsoochak.telemetry.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class OpenApiConfig {

    /** OpenAPI name of the {@code X-Api-Key} scheme; referenced by {@code @SecurityRequirement}. */
    public static final String API_KEY_SCHEME = "ApiKey";

    @Bean
    public OpenAPI telemetryServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Telemetry Service API")
                        .description("Microservice responsible for telemetry ingestion and meter reading workflows "
                                + "in the JalSoochak platform.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("JalSoochak Team")))
                .servers(List.of(
                        new Server().url("/").description("Dev server"),
                        new Server().url("http://localhost:8080").description("Local API gateway (:8080)"),
                        new Server().url("http://localhost:8089").description("Local service (direct, this application.yml)"),
                        new Server().url("http://localhost:8084").description("Local service (README default :8084)")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"))
                .components(new Components()
                        .addSecuritySchemes("Bearer",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"))
                        // Server-to-server ingestion (the /readings routes and the yesterday-final-reading
                        // correction) authenticates with a per-tenant API key, not a JWT. Declared here so
                        // the published contract states the requirement instead of leaving readers to infer
                        // it from the handler code.
                        .addSecuritySchemes(API_KEY_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(TelemetryApiKeyAuthFilter.API_KEY_HEADER)
                                        .description("Per-tenant ingestion key. Resolves the calling tenant; "
                                                + "requests without a valid key are rejected with 401.")));
    }
}
