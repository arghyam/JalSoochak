package org.arghyam.jalsoochak.user.repository;

import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the scope lookups that decide whether an officer may read a given pump operator or
 * scheme. These are the queries standing between an authenticated caller and the rest of the
 * tenant's operator PII, so each rule is asserted against a real database.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("PumpOperatorAccessRepository Integration Tests")
class PumpOperatorAccessRepositoryIntegrationTest extends AbstractPostgresIT {

    private static final String SCHEMA = "tenant_mp";
    private static final int PUMP_OPERATOR_TYPE = 4;
    private static final int SECTION_OFFICER_TYPE = 3;

    @Autowired PumpOperatorAccessRepository repo;
    @Autowired PiiEncryptionService pii;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM tenant_mp.user_scheme_mapping_table");
        jdbc.execute("DELETE FROM tenant_mp.scheme_master_table");
        jdbc.execute("DELETE FROM tenant_mp.user_table");
    }

    private long insertUser(String phone, int userType, String name) {
        return jdbc.queryForObject("""
                INSERT INTO tenant_mp.user_table
                    (tenant_id, title, title_hash, phone_number, phone_number_hash, user_type, status,
                     email_verification_status, phone_verification_status, created_at, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, 1, true, true, NOW(), NOW())
                RETURNING id
                """, Long.class,
                pii.encrypt(name), pii.hmac(name.trim().toLowerCase()),
                pii.encrypt(phone), pii.hmac(phone), userType);
    }

    private long insertScheme(String stateSchemeId) {
        return jdbc.queryForObject("""
                INSERT INTO tenant_mp.scheme_master_table
                    (state_scheme_id, centre_scheme_id, scheme_name, work_status, operating_status)
                VALUES (?, 'C-1', 'Test Scheme', 1, 1)
                RETURNING id
                """, Long.class, stateSchemeId);
    }

    private void mapUserToScheme(long userId, long schemeId, int status, boolean deleted) {
        jdbc.update("""
                INSERT INTO tenant_mp.user_scheme_mapping_table
                    (user_id, scheme_id, status, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, NOW(), NOW(), CASE WHEN ? THEN NOW() ELSE NULL END)
                """, userId, schemeId, status, deleted);
    }

    private void mapUserToScheme(long userId, long schemeId) {
        mapUserToScheme(userId, schemeId, 1, false);
    }

    @Nested
    @DisplayName("sharesActiveSchemeWith")
    class SharesActiveSchemeWith {

        @Test
        @DisplayName("allows an officer to reach an operator on a scheme they both hold")
        void trueForSharedScheme() {
            long officer = insertUser("910000000001", SECTION_OFFICER_TYPE, "Officer One");
            long operator = insertUser("910000000002", PUMP_OPERATOR_TYPE, "Operator One");
            long scheme = insertScheme("S-1");
            mapUserToScheme(officer, scheme);
            mapUserToScheme(operator, scheme);

            assertThat(repo.sharesActiveSchemeWith(SCHEMA, officer, operator)).isTrue();
        }

        @Test
        @DisplayName("refuses an operator on a scheme the officer does not hold — the enumeration case")
        void falseForUnrelatedOperator() {
            long officer = insertUser("910000000001", SECTION_OFFICER_TYPE, "Officer One");
            long stranger = insertUser("910000000003", PUMP_OPERATOR_TYPE, "Operator Two");
            mapUserToScheme(officer, insertScheme("S-1"));
            mapUserToScheme(stranger, insertScheme("S-2"));

            assertThat(repo.sharesActiveSchemeWith(SCHEMA, officer, stranger)).isFalse();
        }

        @Test
        @DisplayName("ignores a soft-deleted mapping on either side")
        void falseForDeletedMapping() {
            long officer = insertUser("910000000001", SECTION_OFFICER_TYPE, "Officer One");
            long operator = insertUser("910000000002", PUMP_OPERATOR_TYPE, "Operator One");
            long scheme = insertScheme("S-1");
            mapUserToScheme(officer, scheme);
            mapUserToScheme(operator, scheme, 1, true);

            assertThat(repo.sharesActiveSchemeWith(SCHEMA, officer, operator)).isFalse();
        }

        @Test
        @DisplayName("ignores an inactive mapping")
        void falseForInactiveMapping() {
            long officer = insertUser("910000000001", SECTION_OFFICER_TYPE, "Officer One");
            long operator = insertUser("910000000002", PUMP_OPERATOR_TYPE, "Operator One");
            long scheme = insertScheme("S-1");
            mapUserToScheme(officer, scheme, 0, false);
            mapUserToScheme(operator, scheme);

            assertThat(repo.sharesActiveSchemeWith(SCHEMA, officer, operator)).isFalse();
        }

        @Test
        @DisplayName("refuses an operator id that does not exist")
        void falseForUnknownOperator() {
            long officer = insertUser("910000000001", SECTION_OFFICER_TYPE, "Officer One");
            mapUserToScheme(officer, insertScheme("S-1"));

            assertThat(repo.sharesActiveSchemeWith(SCHEMA, officer, 999_999L)).isFalse();
        }
    }

    @Nested
    @DisplayName("isMappedToScheme")
    class IsMappedToScheme {

        @Test
        @DisplayName("allows a scheme assigned to the caller and refuses one that is not")
        void mappedOnly() {
            long officer = insertUser("910000000001", SECTION_OFFICER_TYPE, "Officer One");
            long mine = insertScheme("S-1");
            long theirs = insertScheme("S-2");
            mapUserToScheme(officer, mine);

            assertThat(repo.isMappedToScheme(SCHEMA, officer, mine)).isTrue();
            assertThat(repo.isMappedToScheme(SCHEMA, officer, theirs)).isFalse();
        }

        @Test
        @DisplayName("ignores a soft-deleted assignment")
        void falseForDeletedMapping() {
            long officer = insertUser("910000000001", SECTION_OFFICER_TYPE, "Officer One");
            long scheme = insertScheme("S-1");
            mapUserToScheme(officer, scheme, 1, true);

            assertThat(repo.isMappedToScheme(SCHEMA, officer, scheme)).isFalse();
        }
    }

    @Nested
    @DisplayName("fails closed")
    class FailsClosed {

        @Test
        @DisplayName("denies access when the tenant schema has no mapping table")
        void falseWhenMappingTableAbsent() {
            assertThat(repo.sharesActiveSchemeWith("tenant_nonexistent", 1L, 2L)).isFalse();
            assertThat(repo.isMappedToScheme("tenant_nonexistent", 1L, 2L)).isFalse();
        }

        @Test
        @DisplayName("rejects a schema name that is not a plain identifier")
        void rejectsUnsafeSchemaName() {
            // @Repository exception translation wraps the IllegalArgumentException; either way the
            // name never reaches the database, and PumpOperatorAccessGuard turns the throw into a denial.
            assertThatThrownBy(() -> repo.isMappedToScheme("tenant_mp; DROP TABLE users", 1L, 2L))
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
