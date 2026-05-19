package org.arghyam.jalsoochak.user.repository;

import org.arghyam.jalsoochak.user.enums.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("DataVersionRepository Integration Tests")
class DataVersionRepositoryIntegrationTest {

    private static final String SCHEMA = "tenant_mp";

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

    @Autowired DataVersionRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetSeed() {
        jdbc.update("UPDATE tenant_mp.data_versions_table SET version = 1 WHERE resource_type = ?",
                ResourceType.STAFF_USERS.key());
        jdbc.update("DELETE FROM tenant_mp.data_versions_table WHERE resource_type <> ?",
                ResourceType.STAFF_USERS.key());
    }

    @Test
    @DisplayName("getCurrent returns the seeded version for STAFF_USERS")
    void getCurrentReturnsSeed() {
        assertThat(repo.getCurrent(SCHEMA, ResourceType.STAFF_USERS)).isEqualTo(1L);
    }

    @Test
    @DisplayName("bump increments and returns the new version atomically")
    void bumpIncrements() {
        long v1 = repo.bump(SCHEMA, ResourceType.STAFF_USERS);
        long v2 = repo.bump(SCHEMA, ResourceType.STAFF_USERS);
        assertThat(v1).isEqualTo(2L);
        assertThat(v2).isEqualTo(3L);
        assertThat(repo.getCurrent(SCHEMA, ResourceType.STAFF_USERS)).isEqualTo(3L);
    }

    @Test
    @DisplayName("invalid schema name is rejected (any exception thrown — never reaches SQL)")
    void rejectsInvalidSchema() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                repo.getCurrent("public; DROP TABLE foo", ResourceType.STAFF_USERS))
                .hasMessageContaining("Invalid schema name");
    }
}
