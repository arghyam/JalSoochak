package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how a rejected submission's state or centre scheme id is resolved to a {@code dim_scheme_table}
 * row. The state id is text (Punjab's are alphanumeric, like {@code TW20826}) matched trimmed and
 * ignoring case; the centre id is an integer. The event's own tenant wins, then a state-id match, then
 * the lowest scheme id.
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SubmissionAttemptRepository.class)
class SubmissionAttemptRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_submission_attempt_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.schemas", () -> "analytics_schema");
    }

    private static final int TENANT = 1;
    private static final int OTHER_TENANT = 2;

    @Autowired
    private SubmissionAttemptRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE analytics_schema.dim_scheme_table, analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);
        insertTenant(TENANT);
        insertTenant(OTHER_TENANT);
    }

    @Test
    void resolvesAnAlphanumericStateSchemeId() {
        insertScheme(TENANT, 101, "TW20826", 0);

        assertThat(resolve(TENANT, "TW20826", null)).contains(new Resolved(101, TENANT));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {" TW20826 ", "tw20826", "Tw20826\t"})
    void matchesTheStateSchemeIdTrimmedAndIgnoringCase(String submitted) {
        insertScheme(TENANT, 101, "TW20826", 0);

        assertThat(resolve(TENANT, submitted, null)).contains(new Resolved(101, TENANT));
    }

    @Test
    void resolvesANumericStateSchemeId() {
        insertScheme(TENANT, 101, "5001", 6001);

        assertThat(resolve(TENANT, "5001", null)).contains(new Resolved(101, TENANT));
    }

    @Test
    void matchesANumericStateSchemeIdAsText_soLeadingZerosNoLongerMatch() {
        insertScheme(TENANT, 101, "123", 0);

        assertThat(resolve(TENANT, "00123", null)).isEmpty();
    }

    @Test
    void fallsBackToTheCentreSchemeId_whenNoStateSchemeIdMatches() {
        insertScheme(TENANT, 101, "TW20826", 6001);

        assertThat(resolve(TENANT, "SPL17673", "6001")).contains(new Resolved(101, TENANT));
    }

    @Test
    void resolvesFromTheCentreSchemeIdAlone() {
        insertScheme(TENANT, 101, "TW20826", 6001);

        assertThat(resolve(TENANT, null, " 6001 ")).contains(new Resolved(101, TENANT));
    }

    @Test
    void resolvesWithoutAPreferredTenant() {
        insertScheme(OTHER_TENANT, 201, "TW20826", 0);

        assertThat(resolve(null, "TW20826", null)).contains(new Resolved(201, OTHER_TENANT));
    }

    @Test
    void prefersTheEventsOwnTenant_overALowerSchemeIdInAnotherTenant() {
        insertScheme(TENANT, 101, "TW20826", 0);
        insertScheme(OTHER_TENANT, 201, "TW20826", 0);

        assertThat(resolve(OTHER_TENANT, "TW20826", null)).contains(new Resolved(201, OTHER_TENANT));
    }

    @Test
    void prefersAStateSchemeIdMatch_overACentreSchemeIdMatchWithALowerSchemeId() {
        insertScheme(TENANT, 101, "SPL17673", 6001);
        insertScheme(TENANT, 102, "TW20826", 0);

        assertThat(resolve(TENANT, "TW20826", "6001")).contains(new Resolved(102, TENANT));
    }

    @Test
    void resolvesASchemeWithSeveralRowsOnce() {
        insertScheme(TENANT, 101, "TW20826", 0, 11);
        insertScheme(TENANT, 101, "TW20826", 0, 12);

        assertThat(resolve(TENANT, "TW20826", null)).contains(new Resolved(101, TENANT));
    }

    @Test
    void isEmpty_whenNothingMatches() {
        insertScheme(TENANT, 101, "TW20826", 6001);

        assertThat(resolve(TENANT, "SPL17673", "7001")).isEmpty();
    }

    private Optional<Resolved> resolve(Integer preferredTenantId, String stateSchemeId, String centreSchemeId) {
        return repository.resolveScheme(preferredTenantId, stateSchemeId, centreSchemeId)
                .map(row -> new Resolved(row[0], row[1]));
    }

    private void insertTenant(int tenantId) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (?, ?, ?, 'IN', 1, NOW(), NOW())
                """, tenantId, "s" + tenantId, "State " + tenantId);
    }

    private void insertScheme(int tenantId, int schemeId, String stateSchemeId, int centreSchemeId) {
        insertScheme(tenantId, schemeId, stateSchemeId, centreSchemeId, 0);
    }

    private void insertScheme(int tenantId, int schemeId, String stateSchemeId, int centreSchemeId,
                              int parentLgdLocationId) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (tenant_id, scheme_id, state_scheme_id, centre_scheme_id, parent_lgd_location_id, created_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                """, tenantId, schemeId, stateSchemeId, centreSchemeId, parentLgdLocationId);
    }

    private record Resolved(int schemeId, int tenantId) {
    }
}
