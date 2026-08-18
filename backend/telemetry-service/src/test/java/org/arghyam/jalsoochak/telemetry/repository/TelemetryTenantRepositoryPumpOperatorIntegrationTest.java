package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHONE-OPTIONAL: integration tests for
 * {@link TelemetryTenantRepository#findFirstPumpOperatorForScheme(String, Long)} against a real
 * PostgreSQL instance.
 *
 * <p>A phone-less submission is credited to whoever this query returns, so the filters (role,
 * soft-deleted mapping, soft-deleted/inactive user) and the deterministic ordering have to hold in
 * the database, not just in the generated SQL — which is all the sibling
 * {@code TelemetryTenantRepositorySchemesQueryTest} can assert.
 */
@Testcontainers
class TelemetryTenantRepositoryPumpOperatorIntegrationTest {

    private static final long SCHEME_WITH_OPERATORS = 33L;
    private static final long SCHEME_WITHOUT_PUMP_OPERATOR = 44L;
    private static final long PRE_MIGRATION_SCHEME = 77L;
    private static final LocalDateTime SOFT_DELETED_AT = LocalDateTime.parse("2026-01-01T00:00:00");

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static final PiiEncryptionService PII = newPiiEncryptionService();

    private static JdbcTemplate jdbcTemplate;

    private static PiiEncryptionService newPiiEncryptionService() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new PiiEncryptionService(key, key);
    }

    @BeforeAll
    static void seedTenantData() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);

        // A section officer mapped first: the lowest mapping id, so it wins on order but must lose on role.
        insertUser("tenant_as", 1, "Section Officer", "919000000001", SECTION_OFFICER, ACTIVE, null, 3);
        insertMapping("tenant_as", 1, 1, SCHEME_WITH_OPERATORS, ACTIVE, null);

        // Pump operator whose mapping was soft-deleted.
        insertUser("tenant_as", 2, "Deleted Mapping", "919000000002", PUMP_OPERATOR, ACTIVE, null, 3);
        insertMapping("tenant_as", 2, 2, SCHEME_WITH_OPERATORS, ACTIVE, SOFT_DELETED_AT);

        // Pump operator whose mapping is inactive.
        insertUser("tenant_as", 3, "Inactive Mapping", "919000000003", PUMP_OPERATOR, ACTIVE, null, 3);
        insertMapping("tenant_as", 3, 3, SCHEME_WITH_OPERATORS, INACTIVE, null);

        // Pump operator who is themselves inactive.
        insertUser("tenant_as", 4, "Inactive User", "919000000004", PUMP_OPERATOR, INACTIVE, null, 3);
        insertMapping("tenant_as", 4, 4, SCHEME_WITH_OPERATORS, ACTIVE, null);

        // Pump operator who was soft-deleted.
        insertUser("tenant_as", 5, "Deleted User", "919000000005", PUMP_OPERATOR, ACTIVE, SOFT_DELETED_AT, 3);
        insertMapping("tenant_as", 5, 5, SCHEME_WITH_OPERATORS, ACTIVE, null);

        // The expected winner: the oldest surviving active pump-operator mapping.
        insertUser("tenant_as", 6, "Ramesh", "919000000006", PUMP_OPERATOR, ACTIVE, null, 5);
        insertMapping("tenant_as", 6, 6, SCHEME_WITH_OPERATORS, ACTIVE, null);

        // A second, later pump operator on the same scheme — never picked, so repeated phone-less
        // submissions always land on the same user.
        insertUser("tenant_as", 7, "Suresh", "919000000007", PUMP_OPERATOR, ACTIVE, null, 5);
        insertMapping("tenant_as", 7, 7, SCHEME_WITH_OPERATORS, ACTIVE, null);

        // A scheme whose only mapped user is a section officer.
        insertMapping("tenant_as", 8, 1, SCHEME_WITHOUT_PUMP_OPERATOR, ACTIVE, null);

        // Pre-migration tenant: user_table has no language_id column.
        insertUser("tenant_zz", 1, "Legacy Operator", "919000000008", PUMP_OPERATOR, ACTIVE, null, null);
        insertMapping("tenant_zz", 1, 1, PRE_MIGRATION_SCHEME, ACTIVE, null);
    }

    private static final int PUMP_OPERATOR = 1;
    private static final int SECTION_OFFICER = 2;
    private static final int ACTIVE = 1;
    private static final int INACTIVE = 0;

    private static void insertUser(String schemaName,
                                   int id,
                                   String title,
                                   String phoneNumber,
                                   int userType,
                                   int status,
                                   LocalDateTime deletedAt,
                                   Integer languageId) {
        String columns = "id, tenant_id, title, email, user_type, phone_number, status, deleted_at";
        String values = "?, 22, ?, ?, ?, ?, ?, ?";
        Object[] args = new Object[]{
                id, PII.encrypt(title), PII.encrypt("user" + id + "@example.invalid"),
                userType, PII.encrypt(phoneNumber), status, deletedAt};

        if (languageId != null) {
            columns = columns + ", language_id";
            values = values + ", ?";
            Object[] withLanguage = new Object[args.length + 1];
            System.arraycopy(args, 0, withLanguage, 0, args.length);
            withLanguage[args.length] = languageId;
            args = withLanguage;
        }

        jdbcTemplate.update(
                "INSERT INTO " + schemaName + ".user_table (" + columns + ") VALUES (" + values + ")", args);
    }

    private static void insertMapping(String schemaName,
                                      int id,
                                      int userId,
                                      long schemeId,
                                      int status,
                                      LocalDateTime deletedAt) {
        jdbcTemplate.update(
                "INSERT INTO " + schemaName + ".user_scheme_mapping_table "
                        + "(id, user_id, scheme_id, status, deleted_at) VALUES (?, ?, ?, ?, ?)",
                id, userId, schemeId, status, deletedAt);
    }

    private TelemetryTenantRepository repository() {
        return new TelemetryTenantRepository(jdbcTemplate, PII);
    }

    @Test
    void returnsOperatorOfOldestActivePumpOperatorMapping() {
        Optional<TelemetryOperator> operator =
                repository().findFirstPumpOperatorForScheme("tenant_as", SCHEME_WITH_OPERATORS);

        assertTrue(operator.isPresent(), "the scheme has active pump operators mapped to it");
        assertEquals(6L, operator.get().id(),
                "must skip the section officer, the soft-deleted mapping, the inactive mapping, "
                        + "the inactive user and the deleted user, and pick the oldest survivor");
        assertEquals("Ramesh", operator.get().title(), "PII columns must come back decrypted");
        assertEquals("919000000006", operator.get().phoneNumber());
        assertEquals(22, operator.get().tenantId());
        assertEquals(5, operator.get().languageId());
    }

    @Test
    void returnsEmptyWhenSchemeHasNoPumpOperatorMapped() {
        Optional<TelemetryOperator> operator =
                repository().findFirstPumpOperatorForScheme("tenant_as", SCHEME_WITHOUT_PUMP_OPERATOR);

        assertTrue(operator.isEmpty(), "only a section officer is mapped, so the caller must fall back");
    }

    @Test
    void degradesToNullLanguageOnSchemaWithoutLanguageColumn() {
        Optional<TelemetryOperator> operator =
                repository().findFirstPumpOperatorForScheme("tenant_zz", PRE_MIGRATION_SCHEME);

        assertTrue(operator.isPresent(), "a missing language_id column must not fail the lookup");
        assertNull(operator.get().languageId());
    }
}
