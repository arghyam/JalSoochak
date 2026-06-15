package org.arghyam.jalsoochak.user.repository;

import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSchemeComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemePumpOperatorsDTO;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.List;

import org.springframework.dao.InvalidDataAccessApiUsageException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("PublicPumpOperatorRepository Integration Tests")
class PublicPumpOperatorRepositoryIntegrationTest extends AbstractPostgresIT {

    private static final String SCHEMA = "tenant_mp";

    @Autowired PublicPumpOperatorRepository repo;
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

    private long insertPumpOperator(String phone, String name) {
        return insertUser(phone, 4, name); // PUMP_OPERATOR = type id 4
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

    private void mapUserToScheme(long userId, long schemeId) {
        jdbc.update("""
                INSERT INTO tenant_mp.user_scheme_mapping_table
                    (user_id, scheme_id, status, created_at, updated_at)
                VALUES (?, ?, 1, NOW(), NOW())
                """, userId, schemeId);
    }

    private void backdateUserCreatedAt(long userId, int daysAgo) {
        jdbc.update("UPDATE tenant_mp.user_table SET created_at = NOW() - (? * INTERVAL '1 day') WHERE id = ?",
                daysAgo, userId);
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
    @DisplayName("validateSchemaName via findPumpOperatorById")
    class ValidateSchemaName {

        @Test
        @DisplayName("throws InvalidDataAccessApiUsageException for invalid schema name")
        void throwsForInvalidSchemaName() {
            assertThatThrownBy(() -> repo.findPumpOperatorById("bad schema!", 1L, 1L, null, null))
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasMessageContaining("Invalid schema name");
        }
    }

    // ── findPumpOperatorById ──────────────────────────────────────────────────

    @Nested
    @DisplayName("findPumpOperatorById")
    class FindPumpOperatorById {

        @Test
        @DisplayName("returns null for non-existent pump operator")
        void returnsNullForMissing() {
            assertThat(repo.findPumpOperatorById(SCHEMA, 999L, 1L, null, null)).isNull();
        }

        @Test
        @DisplayName("returns operator details without readings")
        void returnsOperatorWithoutReadings() {
            long poId = insertPumpOperator("919876540001", "Test PO");
            long schemeId = insertScheme("FPO-0");
            mapUserToScheme(poId, schemeId);

            PumpOperatorDetailsDTO dto = repo.findPumpOperatorById(SCHEMA, poId, schemeId, null, null);
            assertThat(dto).isNotNull();
            assertThat(dto.id()).isEqualTo(poId);
            assertThat(dto.lastSubmissionAt()).isNull();
        }

        @Test
        @DisplayName("returns operator with scheme mapping and readings")
        void returnsOperatorWithReadings() {
            long poId = insertPumpOperator("919876540002", "PO With Readings");
            long schemeId = insertScheme("FPO-1");
            mapUserToScheme(poId, schemeId);
            insertReading(schemeId, poId, 150.0, LocalDate.now().minusDays(1));

            PumpOperatorDetailsDTO dto = repo.findPumpOperatorById(SCHEMA, poId, schemeId, null, null);
            assertThat(dto).isNotNull();
            assertThat(dto.schemeId()).isEqualTo((int) schemeId);
            assertThat(dto.lastSubmissionAt()).isNotNull();
        }

        @Test
        @DisplayName("does not return non-pump-operator users")
        void ignoresNonPumpOperator() {
            long soId = insertUser("919876540003", 3, "Section Officer"); // SECTION_OFFICER
            assertThat(repo.findPumpOperatorById(SCHEMA, soId, 1L, null, null)).isNull();
        }

        @Test
        @DisplayName("returns missed submission days for requested range even when no readings exist in range")
        void returnsMissedDaysForRequestedRangeWithoutReadingsInRange() {
            long poId = insertPumpOperator("919876540017", "PO Historic Reading");
            long schemeId = insertScheme("FPO-4");
            mapUserToScheme(poId, schemeId);
            insertReading(schemeId, poId, 150.0, LocalDate.of(2026, 3, 17));

            LocalDate startDate = LocalDate.of(2026, 6, 1);
            LocalDate endDate = LocalDate.of(2026, 6, 15);

            PumpOperatorDetailsDTO dto = repo.findPumpOperatorById(SCHEMA, poId, schemeId, startDate, endDate);

            List<LocalDate> expectedMissedDays = IntStream.rangeClosed(0, 14)
                    .mapToObj(startDate::plusDays)
                    .collect(Collectors.toList());

            assertThat(dto).isNotNull();
            assertThat(dto.lastSubmissionAt()).isNull();
            assertThat(dto.firstSubmissionDate()).isNull();
            assertThat(dto.submittedDays()).isEqualTo(0);
            assertThat(dto.totalDaysSinceFirstSubmission()).isEqualTo(15);
            assertThat(dto.reportingRatePercent()).isNotNull();
            assertThat(dto.reportingRatePercent()).isEqualByComparingTo("0.00");
            assertThat(dto.missedSubmissionDays()).containsExactlyElementsOf(expectedMissedDays);
        }

        @Test
        @DisplayName("returns null when operator is not mapped to the requested scheme")
        void returnsNullForDifferentScheme() {
            long poId = insertPumpOperator("919876540003", "Wrong Scheme PO");
            long mappedSchemeId = insertScheme("FPO-2");
            long otherSchemeId = insertScheme("FPO-3");
            mapUserToScheme(poId, mappedSchemeId);

            assertThat(repo.findPumpOperatorById(SCHEMA, poId, otherSchemeId, null, null)).isNull();
        }
    }

    // ── listPumpOperatorsByScheme ─────────────────────────────────────────────

    @Nested
    @DisplayName("listPumpOperatorsByScheme")
    class ListPumpOperatorsByScheme {

        @Test
        @DisplayName("returns empty list when no pump operators are mapped")
        void returnsEmptyWhenNone() {
            long schemeId = insertScheme("LPBS-1");
            List<SchemePumpOperatorsDTO> result =
                    repo.listPumpOperatorsByScheme(SCHEMA, List.of(schemeId), null, null, null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns pump operators grouped by scheme without pagination")
        void returnsPumpOperatorsGroupedByScheme() {
            long po1 = insertPumpOperator("919876540004", "PO Alpha");
            long po2 = insertPumpOperator("919876540005", "PO Beta");
            long schemeId = insertScheme("LPBS-2");
            mapUserToScheme(po1, schemeId);
            mapUserToScheme(po2, schemeId);

            List<SchemePumpOperatorsDTO> result =
                    repo.listPumpOperatorsByScheme(SCHEMA, List.of(schemeId), null, null, null);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).pumpOperators()).hasSize(2);
        }

        @Test
        @DisplayName("filters by scheme name ILIKE")
        void filtersBySchemeName() {
            long po = insertPumpOperator("919876540006", "PO Scheme Name");
            long schemeId = insertScheme("LPBS-3");
            mapUserToScheme(po, schemeId);

            List<SchemePumpOperatorsDTO> matchResult =
                    repo.listPumpOperatorsByScheme(SCHEMA, null, "Test", null, null);
            assertThat(matchResult).hasSize(1);

            List<SchemePumpOperatorsDTO> noMatchResult =
                    repo.listPumpOperatorsByScheme(SCHEMA, null, "NoMatch", null, null);
            assertThat(noMatchResult).isEmpty();
        }

        @Test
        @DisplayName("returns paginated results with page metadata")
        void returnsPaginatedResults() {
            long po1 = insertPumpOperator("919876540007", "PO Page One");
            long po2 = insertPumpOperator("919876540008", "PO Page Two");
            long po3 = insertPumpOperator("919876540009", "PO Page Three");
            long schemeId = insertScheme("LPBS-4");
            mapUserToScheme(po1, schemeId);
            mapUserToScheme(po2, schemeId);
            mapUserToScheme(po3, schemeId);

            List<SchemePumpOperatorsDTO> page0 =
                    repo.listPumpOperatorsByScheme(SCHEMA, List.of(schemeId), null, 0, 2);
            assertThat(page0).hasSize(1);
            assertThat(page0.get(0).totalPumpOperators()).isEqualTo(3L);
            assertThat(page0.get(0).pumpOperators()).hasSize(2);
        }
    }

    // ── getReadingCompliance ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getReadingCompliance")
    class GetReadingCompliance {

        @Test
        @DisplayName("returns null for non-existent pump operator")
        void returnsNullForMissing() {
            assertThat(repo.getReadingCompliance(SCHEMA, 999L)).isNull();
        }

        @Test
        @DisplayName("returns compliance with null submission for operator without readings")
        void returnsComplianceWithoutReadings() {
            long poId = insertPumpOperator("919876540010", "PO Compliance");
            PumpOperatorReadingComplianceDTO dto = repo.getReadingCompliance(SCHEMA, poId);
            assertThat(dto).isNotNull();
            assertThat(dto.lastSubmissionAt()).isNull();
            assertThat(dto.confirmedReading()).isNull();
        }

        @Test
        @DisplayName("returns last submission timestamp and reading")
        void returnsLastSubmission() {
            long poId = insertPumpOperator("919876540011", "PO With Compliance");
            long schemeId = insertScheme("GRC-1");
            insertReading(schemeId, poId, 250.0, LocalDate.now().minusDays(1));

            PumpOperatorReadingComplianceDTO dto = repo.getReadingCompliance(SCHEMA, poId);
            assertThat(dto).isNotNull();
            assertThat(dto.lastSubmissionAt()).isNotNull();
            assertThat(dto.confirmedReading()).isNotNull();
        }
    }

    // ── listReadingCompliance ─────────────────────────────────────────────────

    @Nested
    @DisplayName("listReadingCompliance")
    class ListReadingCompliance {

        @Test
        @DisplayName("returns empty list when no pump operators exist")
        void returnsEmptyWhenNoPumpOperators() {
            List<PumpOperatorReadingComplianceRowDTO> result =
                    repo.listReadingCompliance(SCHEMA, 0, 10);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns pump operators with compliance data")
        void returnsPumpOperatorsWithCompliance() {
            long po1 = insertPumpOperator("919876540012", "PO List One");
            insertPumpOperator("919876540013", "PO List Two");
            long schemeId = insertScheme("LRC-1");
            insertReading(schemeId, po1, 100.0, LocalDate.now().minusDays(1));

            List<PumpOperatorReadingComplianceRowDTO> result =
                    repo.listReadingCompliance(SCHEMA, 0, 10);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("respects offset and limit pagination")
        void respectsOffsetAndLimit() {
            insertPumpOperator("919876540014", "PO Paginate A");
            insertPumpOperator("919876540015", "PO Paginate B");
            insertPumpOperator("919876540016", "PO Paginate C");

            List<PumpOperatorReadingComplianceRowDTO> page1 =
                    repo.listReadingCompliance(SCHEMA, 0, 2);
            assertThat(page1).hasSize(2);

            List<PumpOperatorReadingComplianceRowDTO> page2 =
                    repo.listReadingCompliance(SCHEMA, 2, 2);
            assertThat(page2).hasSize(1);
        }
    }

    // ── countReadingCompliance ────────────────────────────────────────────────

    @Nested
    @DisplayName("countReadingCompliance")
    class CountReadingCompliance {

        @Test
        @DisplayName("returns 0 when no pump operators")
        void returnsZeroWhenEmpty() {
            assertThat(repo.countReadingCompliance(SCHEMA)).isZero();
        }

        @Test
        @DisplayName("returns correct count of pump operators")
        void returnsCorrectCount() {
            insertPumpOperator("919876540017", "PO Count A");
            insertPumpOperator("919876540018", "PO Count B");
            insertUser("919876540019", 3, "Section Officer"); // not a pump operator

            assertThat(repo.countReadingCompliance(SCHEMA)).isEqualTo(2);
        }
    }

    // ── countPumpOperatorsBySchemeWithCompliance ──────────────────────────────

    @Nested
    @DisplayName("countPumpOperatorsBySchemeWithCompliance")
    class CountPumpOperatorsBySchemeWithCompliance {

        @Test
        @DisplayName("returns 0 for missing schema")
        void returnsZeroForMissingSchema() {
            assertThat(repo.countPumpOperatorsBySchemeWithCompliance("tenant_xx", 1L, 1L, null, null)).isZero();
        }

        @Test
        @DisplayName("returns 0 when operator has no readings")
        void returnsZeroWithNoReadings() {
            long poId = insertPumpOperator("919876540020", "PO No Read Compliance");
            long schemeId = insertScheme("CPC-1");
            mapUserToScheme(poId, schemeId);

            assertThat(repo.countPumpOperatorsBySchemeWithCompliance(SCHEMA, schemeId, poId, null, null)).isZero();
        }

        @Test
        @DisplayName("returns count of distinct operators with at least one reading in scheme")
        void returnsCorrectCountWithReadings() {
            long poId = insertPumpOperator("919876540021", "PO Read Compliance");
            backdateUserCreatedAt(poId, 10);
            long schemeId = insertScheme("CPC-2");
            mapUserToScheme(poId, schemeId);
            insertReading(schemeId, poId, 100.0, LocalDate.now().minusDays(2));
            insertReading(schemeId, poId, 200.0, LocalDate.now().minusDays(1));

            assertThat(repo.countPumpOperatorsBySchemeWithCompliance(SCHEMA, schemeId, poId, null, null)).isEqualTo(1);
        }
    }

    // ── listPumpOperatorsBySchemeWithCompliance ───────────────────────────────

    @Nested
    @DisplayName("listPumpOperatorsBySchemeWithCompliance")
    class ListPumpOperatorsBySchemeWithCompliance {

        @Test
        @DisplayName("returns empty list for missing schema")
        void returnsEmptyForMissingSchema() {
            List<PumpOperatorSchemeComplianceRowDTO> result =
                    repo.listPumpOperatorsBySchemeWithCompliance("tenant_xx", 1L, 1L, null, null, 0, 10);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when no readings exist")
        void returnsEmptyWithNoReadings() {
            long poId = insertPumpOperator("919876540022", "PO No Compliance List");
            long schemeId = insertScheme("LPSC-1");
            mapUserToScheme(poId, schemeId);

            List<PumpOperatorSchemeComplianceRowDTO> result =
                    repo.listPumpOperatorsBySchemeWithCompliance(SCHEMA, schemeId, poId, null, null, 0, 10);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns compliance rows with reading data")
        void returnsComplianceRowsWithReadings() {
            long poId = insertPumpOperator("919876540023", "PO Compliance List");
            backdateUserCreatedAt(poId, 10);
            long schemeId = insertScheme("LPSC-2");
            mapUserToScheme(poId, schemeId);
            insertReading(schemeId, poId, 100.0, LocalDate.now().minusDays(1));
            insertReading(schemeId, poId, 200.0, LocalDate.now().minusDays(2));

            List<PumpOperatorSchemeComplianceRowDTO> result =
                    repo.listPumpOperatorsBySchemeWithCompliance(SCHEMA, schemeId, poId, null, null, 0, 10);
            assertThat(result).hasSize(2);
            assertThat(result.get(0).schemeId()).isEqualTo(schemeId);
            assertThat(result.get(0).confirmedReading()).isNotNull();
        }

        @Test
        @DisplayName("respects offset and limit pagination")
        void respectsPagination() {
            long poId = insertPumpOperator("919876540024", "PO Pagination");
            backdateUserCreatedAt(poId, 10);
            long schemeId = insertScheme("LPSC-3");
            mapUserToScheme(poId, schemeId);
            for (int i = 1; i <= 5; i++) {
                insertReading(schemeId, poId, i * 10.0, LocalDate.now().minusDays(i));
            }

            List<PumpOperatorSchemeComplianceRowDTO> page =
                    repo.listPumpOperatorsBySchemeWithCompliance(SCHEMA, schemeId, poId, null, null, 0, 3);
            assertThat(page).hasSize(3);
        }

        @Test
        @DisplayName("returns empty list when operator is not mapped to requested scheme")
        void returnsEmptyForWrongScheme() {
            long poId = insertPumpOperator("919876540025", "PO Wrong Scheme");
            backdateUserCreatedAt(poId, 10);
            long mappedSchemeId = insertScheme("LPSC-4");
            long otherSchemeId = insertScheme("LPSC-5");
            mapUserToScheme(poId, mappedSchemeId);
            insertReading(mappedSchemeId, poId, 100.0, LocalDate.now().minusDays(1));

            List<PumpOperatorSchemeComplianceRowDTO> result =
                    repo.listPumpOperatorsBySchemeWithCompliance(SCHEMA, otherSchemeId, poId, null, null, 0, 10);
            assertThat(result).isEmpty();
        }
    }
}
