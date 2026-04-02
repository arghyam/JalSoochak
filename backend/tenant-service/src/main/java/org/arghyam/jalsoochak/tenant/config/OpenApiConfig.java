package org.arghyam.jalsoochak.tenant.config;

import java.util.List;

import org.springdoc.core.customizers.OpenApiCustomizer;
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

    /**
     * springdoc resolves {@code Void} to an empty object schema {@code {}}, but
     * {@code @JsonInclude(NON_NULL)} suppresses the null field at runtime.
     * This customizer removes {@code data} from {@code ApiResponseDTOVoid} so the
     * spec matches the actual serialized output.
     */
    @Bean
    public OpenApiCustomizer voidResponseSchemaCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) return;
            var schemas = openApi.getComponents().getSchemas();
            if (schemas == null) return;
            var voidSchema = schemas.get("ApiResponseDTOVoid");
            if (voidSchema != null && voidSchema.getProperties() != null) {
                voidSchema.getProperties().remove("data");
            }
        };
    }

    @Bean
    public OpenAPI tenantServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Tenant Service API")
                        .description("Microservice responsible for tenant onboarding, schema provisioning, "
                                + "and tenant configuration management in the JalSoochak platform.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("JalSoochak Team")))
                .servers(List.of(
                        new Server().url("/").description("Dev server"),
                        new Server().url("http://localhost:8080").description("Local API gateway (:8080)"),
                        new Server().url("http://localhost:8081").description("Local service (direct, README :8081)")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"))
                .components(new Components().addSecuritySchemes("Bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
