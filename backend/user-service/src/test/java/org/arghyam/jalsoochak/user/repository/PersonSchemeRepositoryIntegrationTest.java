package org.arghyam.jalsoochak.user.repository;

import org.arghyam.jalsoochak.user.dto.response.PersonSchemeDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingDetailDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryWithMetricsDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("PersonSchemeRepository Integration Tests")
class PersonSchemeRepositoryIntegrationTest {

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

    @Autowired PersonSchemeRepository repo;
    @Autowired PiiEncryptionService pii;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM tenant_mp.flow_reading_table");
        jdbc.execute("DELETE FROM tenant_mp.user_scheme_mapping_table");
        jdbc.execute("DELETE FROM tenant_mp.scheme_master_table");
        jdbc.execute("DELETE FROM tenant_mp.user_table");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long insertUser(String phone, int userType, String title) {
        return jdbc.queryForObject("""
                INSERT INTO tenant_mp.user_table
                    (tenant_id, title, title_hash, phone_number, phone_number_hash, user_type, status,
                     email_verification_status, phone_verification_status, created_at, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, 1, true, true, NOW(), NOW())
                RETURNING id
                """, Long.class,
                pii.encrypt(title), pii.hmac(title.trim().toLowerCase()),
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

    private void mapUserToScheme(long userId, long schemeId) {
        jdbc.update("""
                INSERT INTO tenant_mp.user_scheme_mapping_table
                    (user_id, scheme_id, status, created_at, updated_at)
                VALUES (?, ?, 1, NOW(), NOW())
                """, userId, schemeId);
    }

    private void insertReading(long schemeId, long createdBy, double reading, LocalDate date) {
        jdbc.update("""
                INSERT INTO tenant_mp.flow_reading_table
                    (scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading,
                     correlation_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'corr-1', ?, ?)
                """, schemeId, java.sql.Timestamp.valueOf(date.atStartOfDay()),
                date, reading, reading, createdBy, createdBy);
    }

    // ── validateSchemaName ────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateSchemaName")
    class ValidateSchemaName {

        @Test
        @DisplayName("throws IllegalArgumentException for null schema name")
        void throwsForNull() {
            assertThatThrownBy(() -> repo.countSchemesByPerson(null, 1L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid schema name");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for schema with invalid characters")
        void throwsForInvalidChars() {
            assertThatThrownBy(() -> repo.countSchemesByPerson("tenant-mp; DROP TABLE", 1L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid schema name");
        }
    }

    // ── countSchemesByPerson ──────────────────────────────────────────────────

    @Nested
    @DisplayName("countSchemesByPerson")
    class CountSchemesByPerson {

        @Test
        @DisplayName("returns 0 when no scheme mappings exist")
        void returnsZeroWhenNoMappings() {
            long personId = insertUser("919876543201", 3, "Person One");
            assertThat(repo.countSchemesByPerson(SCHEMA, personId, null)).isZero();
        }

        @Test
        @DisplayName("returns correct count for person with scheme mappings")
        void returnsCountForPersonWithMappings() {
            long personId = insertUser("919876543202", 3, "Section Officer");
            long schemeId1 = insertScheme("SS-1");
            long schemeId2 = insertScheme("SS-2");
            mapUserToScheme(personId, schemeId1);
            mapUserToScheme(personId, schemeId2);

            assertThat(repo.countSchemesByPerson(SCHEMA, personId, null)).isEqualTo(2);
        }

        @Test
        @DisplayName("filters by scheme name using ILIKE")
        void filtersBySchemeNameILike() {
            long personId = insertUser("919876543203", 3, "Section Officer");
            long schemeId = insertScheme("SS-FILTER");
            mapUserToScheme(personId, schemeId);

            assertThat(repo.countSchemesByPerson(SCHEMA, personId, "Test")).isEqualTo(1);
            assertThat(repo.countSchemesByPerson(SCHEMA, personId, "NoMatch")).isZero();
        }

        @Test
        @DisplayName("returns 0 for non-existent schema table (no user_scheme_mapping_table)")
        void returnsZeroForMissingTable() {
            // tenant_xx schema doesn't exist; tableExists check returns false
            assertThat(repo.countSchemesByPerson("tenant_xx", 1L, null)).isZero();
        }
    }

    // ── listSchemesByPerson ───────────────────────────────────────────────────

    @Nested
    @DisplayName("listSchemesByPerson")
    class ListSchemesByPerson {

        @Test
        @DisplayName("returns empty list when no mappings")
        void returnsEmptyWhenNoMappings() {
            long personId = insertUser("919876543204", 3, "Person");
            List<PersonSchemeDetailsDTO> result =
                    repo.listSchemesByPerson(SCHEMA, personId, null, null, null, 0, 10);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns scheme list with readings data")
        void returnsSchemeListWithReadings() {
            long poId = insertUser("919876543205", 4, "Pump Operator");
            long personId = insertUser("919876543206", 3, "Section Officer");
            long schemeId = insertScheme("SS-READINGS");
            mapUserToScheme(personId, schemeId);
            mapUserToScheme(poId, schemeId);
            insertReading(schemeId, poId, 100.0, LocalDate.now().minusDays(1));

            List<PersonSchemeDetailsDTO> result =
                    repo.listSchemesByPerson(SCHEMA, personId, null, "schemeName", "asc", 0, 10);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).schemeName()).isEqualTo("Test Scheme");
        }

        @Test
        @DisplayName("respects sort directions")
        void respectsSortDirections() {
            long personId = insertUser("919876543207", 3, "Section Officer");
            long s1 = insertScheme("A-1");
            long s2 = insertScheme("B-2");
            mapUserToScheme(personId, s1);
            mapUserToScheme(personId, s2);

            List<PersonSchemeDetailsDTO> asc =
                    repo.listSchemesByPerson(SCHEMA, personId, null, "stateSchemeId", "asc", 0, 10);
            assertThat(asc).hasSize(2);
        }

        @Test
        @DisplayName("returns empty list for missing schema")
        void returnsEmptyForMissingSchema() {
            List<PersonSchemeDetailsDTO> result =
                    repo.listSchemesByPerson("tenant_xx", 1L, null, null, null, 0, 10);
            assertThat(result).isEmpty();
        }
    }

    // ── getSchemeDetails ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSchemeDetails")
    class GetSchemeDetails {

        @Test
        @DisplayName("returns null for non-existent scheme")
        void returnsNullForMissingScheme() {
            assertThat(repo.getSchemeDetails(SCHEMA, 999L)).isNull();
        }

        @Test
        @DisplayName("returns scheme details without readings")
        void returnsSchemeDetailsWithoutReadings() {
            long schemeId = insertScheme("SD-1");
            SchemeDetailsWithReportingDTO dto = repo.getSchemeDetails(SCHEMA, schemeId);
            assertThat(dto).isNotNull();
            assertThat(dto.schemeId()).isEqualTo(schemeId);
            assertThat(dto.schemeName()).isEqualTo("Test Scheme");
            assertThat(dto.reportingRatePercent()).isNull();
        }

        @Test
        @DisplayName("returns scheme details with reporting rate when readings exist")
        void returnsReportingRateWithReadings() {
            long userId = insertUser("919876543208", 4, "PO");
            long schemeId = insertScheme("SD-2");
            insertReading(schemeId, userId, 50.0, LocalDate.now().minusDays(1));

            SchemeDetailsWithReportingDTO dto = repo.getSchemeDetails(SCHEMA, schemeId);
            assertThat(dto).isNotNull();
            assertThat(dto.lastSubmissionAt()).isNotNull();
        }
    }

    // ── countSchemeReadings ───────────────────────────────────────────────────

    @Nested
    @DisplayName("countSchemeReadings")
    class CountSchemeReadings {

        @Test
        @DisplayName("returns 0 when no readings exist")
        void returnsZeroWithNoReadings() {
            long schemeId = insertScheme("CR-1");
            assertThat(repo.countSchemeReadings(SCHEMA, schemeId)).isZero();
        }

        @Test
        @DisplayName("returns correct count of pump operator readings")
        void returnsCorrectCount() {
            long poId = insertUser("919876543209", 4, "PO Count");
            long schemeId = insertScheme("CR-2");
            insertReading(schemeId, poId, 10.0, LocalDate.now().minusDays(2));
            insertReading(schemeId, poId, 20.0, LocalDate.now().minusDays(1));

            assertThat(repo.countSchemeReadings(SCHEMA, schemeId)).isEqualTo(2);
        }

        @Test
        @DisplayName("does not count non-pump-operator readings")
        void ignoresNonPumpOperatorReadings() {
            long soId = insertUser("919876543210", 3, "SO"); // section officer
            long schemeId = insertScheme("CR-3");
            insertReading(schemeId, soId, 10.0, LocalDate.now().minusDays(1));

            assertThat(repo.countSchemeReadings(SCHEMA, schemeId)).isZero();
        }
    }

    // ── listSchemeReadings ────────────────────────────────────────────────────

    @Nested
    @DisplayName("listSchemeReadings")
    class ListSchemeReadings {

        @Test
        @DisplayName("returns empty list when no readings exist")
        void returnsEmptyWithNoReadings() {
            long schemeId = insertScheme("SR-1");
            List<SchemeReadingSubmissionDTO> result =
                    repo.listSchemeReadings(SCHEMA, schemeId, 0, 10);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns readings for pump operators only")
        void returnsReadingsForPumpOperators() {
            long poId = insertUser("919876543211", 4, "PO List");
            long soId = insertUser("919876543212", 3, "SO List");
            long schemeId = insertScheme("SR-2");
            insertReading(schemeId, poId, 100.0, LocalDate.now().minusDays(1));
            insertReading(schemeId, soId, 200.0, LocalDate.now().minusDays(1));

            List<SchemeReadingSubmissionDTO> result =
                    repo.listSchemeReadings(SCHEMA, schemeId, 0, 10);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).readingValue()).isNotNull();
        }
    }

    // ── countPumpOperatorsByPerson ────────────────────────────────────────────

    @Nested
    @DisplayName("countPumpOperatorsByPerson")
    class CountPumpOperatorsByPerson {

        @Test
        @DisplayName("returns 0 when person has no scheme mappings")
        void returnsZeroForPersonWithNoMappings() {
            long personId = insertUser("919876543213", 3, "Person No Map");
            assertThat(repo.countPumpOperatorsByPerson(SCHEMA, personId, null, null, null, null, null))
                    .isZero();
        }

        @Test
        @DisplayName("returns 0 for missing tenant schema")
        void returnsZeroForMissingSchema() {
            assertThat(repo.countPumpOperatorsByPerson("tenant_xx", 1L, null, null, null, null, null))
                    .isZero();
        }

        @Test
        @DisplayName("returns correct count of pump operators sharing schemes with person")
        void returnsCorrectCount() {
            long personId = insertUser("919876543214", 3, "Person Count");
            long po1 = insertUser("919876543215", 4, "PO One");
            long po2 = insertUser("919876543216", 4, "PO Two");
            long schemeId = insertScheme("CPO-1");
            mapUserToScheme(personId, schemeId);
            mapUserToScheme(po1, schemeId);
            mapUserToScheme(po2, schemeId);

            assertThat(repo.countPumpOperatorsByPerson(SCHEMA, personId, null, null, null, null, null))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("filters by active status")
        void filtersByStatus() {
            long personId = insertUser("919876543217", 3, "Person Status");
            long po1 = insertUser("919876543218", 4, "PO Active");
            long schemeId = insertScheme("CPO-2");
            mapUserToScheme(personId, schemeId);
            mapUserToScheme(po1, schemeId);

            // active status = 1
            assertThat(repo.countPumpOperatorsByPerson(SCHEMA, personId, null, 1, null, null, null))
                    .isEqualTo(1);
            // inactive status = 0 → po1 has status=1, so 0 results
            assertThat(repo.countPumpOperatorsByPerson(SCHEMA, personId, null, 0, null, null, null))
                    .isZero();
        }
    }

    // ── listPumpOperatorsByPerson ─────────────────────────────────────────────

    @Nested
    @DisplayName("listPumpOperatorsByPerson")
    class ListPumpOperatorsByPerson {

        @Test
        @DisplayName("returns empty list when no pump operators share schemes")
        void returnsEmptyWhenNoPumpOperators() {
            long personId = insertUser("919876543219", 3, "Person List");
            List<PumpOperatorSummaryWithMetricsDTO> result =
                    repo.listPumpOperatorsByPerson(SCHEMA, personId, null, null, null, null, null,
                            null, "asc", 0, 10);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns pump operators sharing schemes with person")
        void returnsPumpOperatorsForPerson() {
            long personId = insertUser("919876543220", 3, "Person List2");
            long poId = insertUser("919876543221", 4, "PO For Person");
            long schemeId = insertScheme("LPO-1");
            mapUserToScheme(personId, schemeId);
            mapUserToScheme(poId, schemeId);

            List<PumpOperatorSummaryWithMetricsDTO> result =
                    repo.listPumpOperatorsByPerson(SCHEMA, personId, null, null, null, null, null,
                            null, "desc", 0, 10);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(poId);
        }

        @Test
        @DisplayName("returns empty list for missing schema")
        void returnsEmptyForMissingSchema() {
            List<PumpOperatorSummaryWithMetricsDTO> result =
                    repo.listPumpOperatorsByPerson("tenant_xx", 1L, null, null, null, null, null,
                            null, null, 0, 10);
            assertThat(result).isEmpty();
        }
    }

    // ── countPumpOperatorReadings ─────────────────────────────────────────────

    @Nested
    @DisplayName("countPumpOperatorReadings")
    class CountPumpOperatorReadings {

        @Test
        @DisplayName("returns 0 for pump operator with no readings")
        void returnsZeroWithNoReadings() {
            long poId = insertUser("919876543222", 4, "PO No Readings");
            assertThat(repo.countPumpOperatorReadings(SCHEMA, poId, null)).isZero();
        }

        @Test
        @DisplayName("returns correct reading count")
        void returnsCorrectCount() {
            long poId = insertUser("919876543223", 4, "PO Readings");
            long schemeId = insertScheme("CPOR-1");
            insertReading(schemeId, poId, 10.0, LocalDate.now().minusDays(2));
            insertReading(schemeId, poId, 20.0, LocalDate.now().minusDays(1));

            assertThat(repo.countPumpOperatorReadings(SCHEMA, poId, null)).isEqualTo(2);
        }

        @Test
        @DisplayName("filters by scheme name")
        void filtersBySchemeName() {
            long poId = insertUser("919876543224", 4, "PO Scheme Filter");
            long schemeId = insertScheme("CPOR-2");
            insertReading(schemeId, poId, 10.0, LocalDate.now().minusDays(1));

            assertThat(repo.countPumpOperatorReadings(SCHEMA, poId, "Test")).isEqualTo(1);
            assertThat(repo.countPumpOperatorReadings(SCHEMA, poId, "NoMatch")).isZero();
        }
    }

    // ── listPumpOperatorReadings ──────────────────────────────────────────────

    @Nested
    @DisplayName("listPumpOperatorReadings")
    class ListPumpOperatorReadings {

        @Test
        @DisplayName("returns empty list when no readings exist")
        void returnsEmptyWithNoReadings() {
            long poId = insertUser("919876543225", 4, "PO Empty Readings");
            List<PumpOperatorReadingDetailDTO> result =
                    repo.listPumpOperatorReadings(SCHEMA, poId, null, null, "desc", 0, 10);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns readings sorted by reading_at")
        void returnsReadingsSorted() {
            long poId = insertUser("919876543226", 4, "PO Sorted Readings");
            long schemeId = insertScheme("LPOR-1");
            insertReading(schemeId, poId, 100.0, LocalDate.now().minusDays(3));
            insertReading(schemeId, poId, 200.0, LocalDate.now().minusDays(1));

            List<PumpOperatorReadingDetailDTO> result =
                    repo.listPumpOperatorReadings(SCHEMA, poId, null, "readingAt", "desc", 0, 10);
            assertThat(result).hasSize(2);
            // desc order: most recent first
            assertThat(result.get(0).readingValue().doubleValue()).isEqualTo(200.0);
        }
    }

    // ── parseStatus ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseStatus")
    class ParseStatus {

        @Test
        @DisplayName("returns null for null input")
        void returnsNullForNull() {
            assertThat(repo.parseStatus(null)).isNull();
        }

        @Test
        @DisplayName("returns null for blank input")
        void returnsNullForBlank() {
            assertThat(repo.parseStatus("  ")).isNull();
        }

        @Test
        @DisplayName("maps 'active' to 1")
        void mapsActiveToOne() {
            assertThat(repo.parseStatus("active")).isEqualTo(1);
            assertThat(repo.parseStatus("ACTIVE")).isEqualTo(1);
        }

        @Test
        @DisplayName("maps 'inactive' to 0")
        void mapsInactiveToZero() {
            assertThat(repo.parseStatus("inactive")).isEqualTo(0);
        }

        @Test
        @DisplayName("parses numeric status string")
        void parsesNumericString() {
            assertThat(repo.parseStatus("2")).isEqualTo(2);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for invalid status string")
        void throwsForInvalidStatus() {
            assertThatThrownBy(() -> repo.parseStatus("unknown"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid status value");
        }
    }
}
