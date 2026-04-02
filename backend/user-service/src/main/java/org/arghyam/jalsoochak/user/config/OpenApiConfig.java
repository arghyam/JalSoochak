package org.arghyam.jalsoochak.user.config;

import java.util.List;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
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
            var schemas = openApi.getComponents().getSchemas();
            if (schemas == null) return;
            var voidSchema = schemas.get("ApiResponseDTOVoid");
            if (voidSchema != null && voidSchema.getProperties() != null) {
                voidSchema.getProperties().remove("data");
            }
        };
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("JalSoochak User Service").version("v1"))
                .servers(List.of(
                        new Server().url("/").description("Dev server"),
                        new Server().url("http://localhost:8080").description("Local API gateway (:8080)"),
                        new Server().url("http://localhost:8082").description("Local service (direct, README :8082)")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"))
                .components(new Components().addSecuritySchemes("Bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
