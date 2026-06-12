package org.arghyam.jalsoochak.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("TenantConfigRepository Integration Tests")
class TenantConfigRepositoryIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void dbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TenantConfigRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM common_schema.tenant_config_master_table");
    }

    private void insertConfig(int tenantId, String key, String value) {
        jdbc.update("""
                INSERT INTO common_schema.tenant_config_master_table
                    (tenant_id, config_key, config_value, updated_at)
                VALUES (?, ?, ?, NOW())
                """, tenantId, key, value);
    }

    @Nested
    @DisplayName("findConfigValue")
    class FindConfigValue {

        @Test
        @DisplayName("returns empty Optional when no config exists for the key")
        void returnsEmptyWhenMissing() {
            Optional<String> result = repo.findConfigValue(1, "nonexistent_key");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns value when config exists")
        void returnsValueWhenPresent() {
            insertConfig(1, "language_1", "Hindi");

            Optional<String> result = repo.findConfigValue(1, "language_1");
            assertThat(result).isPresent().hasValue("Hindi");
        }

        @Test
        @DisplayName("returns empty when config exists for different tenant")
        void returnsEmptyForDifferentTenant() {
            insertConfig(1, "some_key", "some_value");

            Optional<String> result = repo.findConfigValue(99, "some_key");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns most recently updated value when multiple entries share the same key")
        void returnsMostRecentValue() {
            // Insert two entries; the one with higher id (later insertion) wins
            insertConfig(1, "nudge_message_hindi", "पुरानी सूचना");
            insertConfig(1, "nudge_message_hindi", "नई सूचना");

            Optional<String> result = repo.findConfigValue(1, "nudge_message_hindi");
            assertThat(result).isPresent().hasValue("नई सूचना");
        }
    }
}
