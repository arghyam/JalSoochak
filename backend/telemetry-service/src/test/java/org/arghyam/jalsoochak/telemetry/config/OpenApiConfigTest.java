package org.arghyam.jalsoochak.telemetry.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiConfigTest {

    @Test
    void openApiListsGatewayLocalGatewayYmlPortAndReadmePort() {
        OpenAPI api = new OpenApiConfig().telemetryServiceOpenAPI(new WebhookAuthProperties());
        assertEquals(4, api.getServers().size());
        assertEquals("/", api.getServers().get(0).getUrl());
        assertEquals("http://localhost:8080", api.getServers().get(1).getUrl());
        assertEquals("http://localhost:8089", api.getServers().get(2).getUrl());
        assertEquals("http://localhost:8084", api.getServers().get(3).getUrl());
    }

    @Test
    void webhookTokenSchemeIsAHeaderApiKeyNamedFromConfiguration() {
        WebhookAuthProperties properties = new WebhookAuthProperties();
        properties.setHeaderName("X-Custom-Webhook-Token");

        SecurityScheme scheme = new OpenApiConfig().telemetryServiceOpenAPI(properties)
                .getComponents().getSecuritySchemes().get(OpenApiConfig.WEBHOOK_TOKEN_SCHEME);

        assertNotNull(scheme);
        assertEquals(SecurityScheme.Type.APIKEY, scheme.getType());
        assertEquals(SecurityScheme.In.HEADER, scheme.getIn());
        assertEquals("X-Custom-Webhook-Token", scheme.getName());
    }

    @Test
    void internalTokenSchemeIsTheHeaderTheInternalFilterReads() {
        SecurityScheme scheme = new OpenApiConfig().telemetryServiceOpenAPI(new WebhookAuthProperties())
                .getComponents().getSecuritySchemes().get(OpenApiConfig.INTERNAL_TOKEN_SCHEME);

        assertNotNull(scheme);
        assertEquals(SecurityScheme.Type.APIKEY, scheme.getType());
        assertEquals(SecurityScheme.In.HEADER, scheme.getIn());
        assertEquals(InternalAuthFilter.TOKEN_HEADER, scheme.getName());
    }
}
