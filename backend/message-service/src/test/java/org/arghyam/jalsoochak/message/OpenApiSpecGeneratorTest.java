package org.arghyam.jalsoochak.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.message.channel.GlificAuthService;
import org.arghyam.jalsoochak.message.channel.GlificWhatsAppService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the full Spring context (with Testcontainers PostgreSQL) and writes
 * the OpenAPI spec to {@code target/openapi.json}.
 *
 * <p>Run with: {@code mvn test -Dtest=OpenApiSpecGeneratorTest}</p>
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                // Prevent Spring Security from fetching OIDC config from Keycloak at startup;
                // jwk-set-uri is loaded lazily (only when a JWT is validated).
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9999/jwks",
                "spring.profiles.active=dev"
        })
@Testcontainers(disabledWithoutDocker = true)
class OpenApiSpecGeneratorTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jalsoochak_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Suppress @PostConstruct login() which makes a live HTTP call to Glific. */
    @MockBean
    GlificAuthService glificAuthService;

    /** Suppress @PostConstruct validateTemplates() to avoid needing all Glific env vars. */
    @MockBean
    GlificWhatsAppService glificWhatsAppService;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void generateOpenApiSpec() throws IOException {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String spec = response.getBody();
        assertThat(spec).isNotBlank();

        JsonNode root = new ObjectMapper().readTree(spec);
        assertThat(root.path("openapi").asText()).isNotBlank();

        Path output = Path.of("target/openapi.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, spec);

        System.out.println("OpenAPI spec written to: " + output.toAbsolutePath());
    }
}
