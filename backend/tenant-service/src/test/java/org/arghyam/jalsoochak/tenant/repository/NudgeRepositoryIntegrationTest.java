package org.arghyam.jalsoochak.tenant.repository;

import org.arghyam.jalsoochak.tenant.service.PiiEncryptionService;
import org.arghyam.jalsoochak.tenant.service.TenantSchedulerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link NudgeRepository} SQL queries against a real
 * PostgreSQL instance via Testcontainers.
 *
 * <p>Verifies the three core queries:
 * <ul>
 *   <li>{@code streamUsersWithNoUploadToday} – nudge candidates</li>
 *   <li>{@code streamUsersWithMissedDays} – escalation candidates</li>
 *   <li>{@code findOfficerByUserType} – officer lookup for escalation</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class NudgeRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Suppress real Kafka connections – these tests only exercise database logic
    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    /** Suppress @PostConstruct scheduling – not under test here. */
    @MockBean
    private TenantSchedulerManager tenantSchedulerManager;

    /** Suppress PII encryption startup – not under test here; decrypt returns input as-is. */
    @MockBean
    private PiiEncryptionService piiEncryptionService;

    @Autowired
    private NudgeRepository nudgeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int operatorTypeId;
    private int sectionOfficerTypeId;
    private int districtOfficerTypeId;
    private int schemeId;

    @BeforeEach
    void setUp() {
        when(piiEncryptionService.safeDecrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(piiEncryptionService.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));

        operatorTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM common_schema.user_type_master_table WHERE UPPER(c_name) = 'PUMP_OPERATOR'",
                Integer.class);
        sectionOfficerTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM common_schema.user_type_master_table WHERE UPPER(c_name) = 'SECTION_OFFICER'",
                Integer.class);
        districtOfficerTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM common_schema.user_type_master_table WHERE UPPER(c_name) = 'DISTRICT_OFFICER'",
                Integer.class);

        schemeId = jdbcTemplate.queryForObject(
                "INSERT INTO tenant_test.scheme_master_table (state_scheme_id) VALUES ('S-001') RETURNING id",
                Integer.class);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM tenant_test.flow_reading_table");
        jdbcTemplate.execute("DELETE FROM tenant_test.user_scheme_mapping_table");
        jdbcTemplate.execute("DELETE FROM tenant_test.user_table");
        jdbcTemplate.execute("DELETE FROM tenant_test.scheme_master_table");
    }

    // ───────────────────────────── streamUsersWithNoUploadToday ─────────────────

    @Test
    void streamUsersWithNoUploadToday_returnsOperator_whenNoReadingSubmittedToday() {
        int opId = insertUser("Op One", "911111111111", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1);

        List<Map<String, Object>> result = collectNoUploadToday("tenant_test");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("phone_number")).isEqualTo("911111111111");
        assertThat(result.get(0).get("name")).isEqualTo("Op One");
        assertThat(((Number) result.get(0).get("user_id")).intValue()).isEqualTo(opId);
        assertThat(result.get(0).get("whatsapp_connection_id")).isNull();
    }

    @Test
    void streamUsersWithNoUploadToday_excludesOperator_whenReadingSubmittedToday() {
        int opId = insertUser("Op Two", "912222222222", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1);
        insertFlowReading(schemeId, opId, LocalDate.now());

        List<Map<String, Object>> result = collectNoUploadToday("tenant_test");

        assertThat(result).isEmpty();
    }

    @Test
    void streamUsersWithNoUploadToday_excludesNonOperatorUserTypes() {
        int officerId = insertUser("SO Officer", "913333333333", sectionOfficerTypeId);
        insertSchemeMapping(officerId, schemeId, 1);

        List<Map<String, Object>> result = collectNoUploadToday("tenant_test");

        assertThat(result).isEmpty();
    }

    @Test
    void streamUsersWithNoUploadToday_excludesOperator_withInactiveSchemeMappingStatus() {
        int opId = insertUser("Op Inactive", "914444444444", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 0); // status=0 means inactive

        List<Map<String, Object>> result = collectNoUploadToday("tenant_test");

        assertThat(result).isEmpty();
    }

    @Test
    void streamUsersWithNoUploadToday_excludesOperator_whenUserIsInactive() {
        int opId = insertInactiveUser("Op Deactivated", "914500000001", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1); // active mapping, but user is inactive

        List<Map<String, Object>> result = collectNoUploadToday("tenant_test");

        assertThat(result).isEmpty();
    }

    @Test
    void streamUsersWithNoUploadToday_includesOperator_whenReadingFromYesterdayOnly() {
        int opId = insertUser("Op Yesterday", "915555555555", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1);
        insertFlowReading(schemeId, opId, LocalDate.now().minusDays(1));

        List<Map<String, Object>> result = collectNoUploadToday("tenant_test");

        assertThat(result).hasSize(1);
    }

    @Test
    void streamUsersWithNoUploadToday_returnsLanguageId() {
        int opId = insertUserWithLanguage("Op Lang", "916601010101", operatorTypeId, 2);
        insertSchemeMapping(opId, schemeId, 1);

        List<Map<String, Object>> result = collectNoUploadToday("tenant_test");

        assertThat(result).hasSize(1);
        assertThat(((Number) result.get(0).get("language_id")).intValue()).isEqualTo(2);
    }

    // ─────────────────────────── streamUsersWithMissedDays ──────────────────────

    @Test
    void streamUsersWithMissedDays_returnsOperator_whoHasNeverUploaded() {
        int opId = insertUser("Op Never", "916666666666", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1);
        // no flow_reading rows → never uploaded

        List<Map<String, Object>> result = collectMissedDays("tenant_test", 3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("phone_number")).isEqualTo("916666666666");
        assertThat(result.get(0).get("days_since_last_upload")).isNull(); // NULL = never uploaded
        assertThat(((Number) result.get(0).get("user_id")).intValue()).isEqualTo(opId);
        assertThat(result.get(0).get("whatsapp_connection_id")).isNull();
    }

    @Test
    void streamUsersWithMissedDays_returnsOperator_whenDaysExceedThreshold() {
        int opId = insertUser("Op Missed", "917777777777", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1);
        insertFlowReading(schemeId, opId, LocalDate.now().minusDays(5));

        List<Map<String, Object>> result = collectMissedDays("tenant_test", 3);

        assertThat(result).hasSize(1);
        Number daysMissed = (Number) result.get(0).get("days_since_last_upload");
        assertThat(daysMissed.intValue()).isEqualTo(5);
    }

    @Test
    void streamUsersWithMissedDays_excludesOperator_whenDaysBelowThreshold() {
        int opId = insertUser("Op Recent", "918888888888", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1);
        insertFlowReading(schemeId, opId, LocalDate.now().minusDays(2));

        List<Map<String, Object>> result = collectMissedDays("tenant_test", 3);

        assertThat(result).isEmpty();
    }

    @Test
    void streamUsersWithMissedDays_returnsOperatorAtExactThreshold() {
        int opId = insertUser("Op Exact", "919191919191", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1);
        insertFlowReading(schemeId, opId, LocalDate.now().minusDays(3));

        List<Map<String, Object>> result = collectMissedDays("tenant_test", 3);

        assertThat(result).hasSize(1);
        assertThat(((Number) result.get(0).get("days_since_last_upload")).intValue()).isEqualTo(3);
    }

    @Test
    void streamUsersWithMissedDays_excludesOperator_whenUserIsInactive() {
        int opId = insertInactiveUser("Op Deactivated Missed", "914500000002", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1); // active mapping, but user is inactive
        // no readings → would normally be included (never uploaded)

        List<Map<String, Object>> result = collectMissedDays("tenant_test", 3);

        assertThat(result).isEmpty();
    }

    @Test
    void streamUsersWithMissedDays_includesSchemeName() {
        jdbcTemplate.update("UPDATE tenant_test.scheme_master_table SET state_scheme_id = 'MY-SCHEME' WHERE id = ?", schemeId);
        int opId = insertUser("Op Scheme", "917070707070", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1);

        List<Map<String, Object>> result = collectMissedDays("tenant_test", 0);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).get("scheme_name")).isEqualTo("MY-SCHEME");
    }

    // ─────────────────────────── findOfficerByUserType ────────────────────────

    @Test
    void findOfficerByUserType_returnsOfficer_whenMappedToScheme() {
        int officerId = insertUser("SO Name", "919999999999", sectionOfficerTypeId);
        insertSchemeMapping(officerId, schemeId, 1);

        Map<String, Object> result = nudgeRepository.findOfficerByUserType(
                "tenant_test", schemeId, "SECTION_OFFICER");

        assertThat(result).isNotNull();
        assertThat(result.get("name")).isEqualTo("SO Name");
        assertThat(result.get("phone_number")).isEqualTo("919999999999");
    }

    @Test
    void findOfficerByUserType_returnsNull_whenNoOfficerMapped() {
        Map<String, Object> result = nudgeRepository.findOfficerByUserType(
                "tenant_test", schemeId, "DISTRICT_OFFICER");

        assertThat(result).isNull();
    }

    @Test
    void findOfficerByUserType_returnsNull_whenUserIsInactive() {
        int officerId = insertInactiveUser("SO Inactive User", "914500000003", sectionOfficerTypeId);
        insertSchemeMapping(officerId, schemeId, 1); // active mapping, but user is inactive

        Map<String, Object> result = nudgeRepository.findOfficerByUserType(
                "tenant_test", schemeId, "SECTION_OFFICER");

        assertThat(result).isNull();
    }

    @Test
    void findOfficerByUserType_returnsNull_whenOfficerHasInactiveMapping() {
        int officerId = insertUser("DO Inactive", "910101010101", districtOfficerTypeId);
        insertSchemeMapping(officerId, schemeId, 0); // inactive mapping

        Map<String, Object> result = nudgeRepository.findOfficerByUserType(
                "tenant_test", schemeId, "DISTRICT_OFFICER");

        assertThat(result).isNull();
    }

    @Test
    void findOfficerByUserType_returnsLanguageId_ofOfficer() {
        int officerId = insertUserWithLanguage("SO Lang", "911122334455", sectionOfficerTypeId, 3);
        insertSchemeMapping(officerId, schemeId, 1);

        Map<String, Object> result = nudgeRepository.findOfficerByUserType(
                "tenant_test", schemeId, "SECTION_OFFICER");

        assertThat(((Number) result.get("language_id")).intValue()).isEqualTo(3);
    }

    @Test
    void findOfficerByUserType_returnsUserIdAndWhatsappConnectionId() {
        int officerId = insertUser("DO Check", "911199887766", districtOfficerTypeId);
        insertSchemeMapping(officerId, schemeId, 1);

        Map<String, Object> result = nudgeRepository.findOfficerByUserType(
                "tenant_test", schemeId, "DISTRICT_OFFICER");

        assertThat(result).isNotNull();
        assertThat(((Number) result.get("user_id")).intValue()).isEqualTo(officerId);
        assertThat(result.get("whatsapp_connection_id")).isNull();
    }

    // ──────────────────────── findAllOfficersByUserType ────────────────────────

    @Test
    void findAllOfficersByUserType_returnsOneEntryPerScheme_withLowestUserId() {
        // Insert two section officers for the same scheme; DISTINCT ON / ORDER BY u.id
        // must pick the one with the lower user id.
        int officerLow  = insertUser("SO First",  "919000000010", sectionOfficerTypeId);
        int officerHigh = insertUser("SO Second", "919000000011", sectionOfficerTypeId);
        insertSchemeMapping(officerLow,  schemeId, 1);
        insertSchemeMapping(officerHigh, schemeId, 1);

        java.util.Map<Object, java.util.Map<String, Object>> result =
                nudgeRepository.findAllOfficersByUserType("tenant_test", "SECTION_OFFICER");

        assertThat(result).hasSize(1);
        java.util.Map<String, Object> chosen = result.get((long) schemeId) != null
                ? result.get((long) schemeId)
                : result.values().iterator().next();
        assertThat(((Number) chosen.get("user_id")).intValue()).isEqualTo(officerLow);
        assertThat(chosen.get("name")).isEqualTo("SO First");
    }

    @Test
    void findAllOfficersByUserType_excludesInactiveUsers() {
        int officerId = insertInactiveUser("SO Inactive User2", "914500000004", sectionOfficerTypeId);
        insertSchemeMapping(officerId, schemeId, 1); // active mapping, but user is inactive

        java.util.Map<Object, java.util.Map<String, Object>> result =
                nudgeRepository.findAllOfficersByUserType("tenant_test", "SECTION_OFFICER");

        assertThat(result).isEmpty();
    }

    @Test
    void findAllOfficersByUserType_excludesInactiveMappings() {
        int officerId = insertUser("SO Inactive", "919000000012", sectionOfficerTypeId);
        insertSchemeMapping(officerId, schemeId, 0); // inactive

        java.util.Map<Object, java.util.Map<String, Object>> result =
                nudgeRepository.findAllOfficersByUserType("tenant_test", "SECTION_OFFICER");

        assertThat(result).isEmpty();
    }

    @Test
    void findAllOfficersByUserType_returnsEmptyMap_whenNoOfficersExist() {
        java.util.Map<Object, java.util.Map<String, Object>> result =
                nudgeRepository.findAllOfficersByUserType("tenant_test", "DISTRICT_OFFICER");

        assertThat(result).isEmpty();
    }

    @Test
    void streamUsersWithNoUploadToday_includesRow_whenDbNameIsEmpty() {
        // A user whose title (name) is an empty string — must still be emitted, not skipped
        int opId = jdbcTemplate.queryForObject(
                "INSERT INTO tenant_test.user_table (title, phone_number, user_type, language_id, email) " +
                "VALUES ('', ?, ?, 0, ?) RETURNING id",
                Integer.class, "911900000010", operatorTypeId, "empty-name@test.com");
        insertSchemeMapping(opId, schemeId, 1);

        List<Map<String, Object>> result = collectNoUploadToday("tenant_test");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("name")).isEqualTo("");
        assertThat(result.get(0).get("phone_number")).isEqualTo("911900000010");
    }

    @Test
    void findOfficerByUserType_decryptionFailure_setsFieldNullAndDoesNotThrow() {
        int officerId = insertUser("Corrupt Officer", "911900000011", sectionOfficerTypeId);
        insertSchemeMapping(officerId, schemeId, 1);

        when(piiEncryptionService.safeDecrypt(eq("Corrupt Officer")))
                .thenThrow(new IllegalStateException("tampered ciphertext"));
        when(piiEncryptionService.safeDecrypt(eq("911900000011"))).thenReturn("911900000011");

        Map<String, Object> result = nudgeRepository.findOfficerByUserType(
                "tenant_test", schemeId, "SECTION_OFFICER");

        // Must not throw; failed field is set to null
        assertThat(result).isNotNull();
        assertThat(result.get("name")).isNull();
        assertThat(result.get("phone_number")).isEqualTo("911900000011");
    }

    @Test
    void findAllOfficersByUserType_decryptionFailureSkipsCorruptedRow() {
        int goodOfficer = insertUser("SO Good", "919000000020", sectionOfficerTypeId);
        int badOfficer  = insertUser("SO Bad",  "919000000021", sectionOfficerTypeId);
        int scheme2Id = jdbcTemplate.queryForObject(
                "INSERT INTO tenant_test.scheme_master_table (state_scheme_id) VALUES ('S-002') RETURNING id",
                Integer.class);
        insertSchemeMapping(goodOfficer, schemeId,  1);
        insertSchemeMapping(badOfficer,  scheme2Id, 1);

        // Corrupt the second officer's name — safeDecrypt throws for exactly that value
        when(piiEncryptionService.safeDecrypt(eq("SO Bad"))).thenThrow(new IllegalStateException("tampered ciphertext"));
        // Good officer's fields still decrypt normally
        when(piiEncryptionService.safeDecrypt(eq("SO Good"))).thenReturn("SO Good");
        when(piiEncryptionService.safeDecrypt(eq("919000000020"))).thenReturn("919000000020");

        java.util.Map<Object, java.util.Map<String, Object>> result =
                nudgeRepository.findAllOfficersByUserType("tenant_test", "SECTION_OFFICER");

        // Corrupted row is skipped; valid row is present; no exception propagates
        assertThat(result).hasSize(1);
        assertThat(result.values().iterator().next().get("name")).isEqualTo("SO Good");

        jdbcTemplate.update("DELETE FROM tenant_test.scheme_master_table WHERE id = ?", scheme2Id);
    }

    // ────────────────────────── updateWhatsAppConnectionId ─────────────────────

    @Test
    void updateWhatsAppConnectionId_persistsContactId() {
        int opId = insertUser("Op WA", "911900000001", operatorTypeId);

        nudgeRepository.updateWhatsAppConnectionId("tenant_test", opId, 9999L);

        Long stored = jdbcTemplate.queryForObject(
                "SELECT whatsapp_connection_id FROM tenant_test.user_table WHERE id = ?",
                Long.class, opId);
        assertThat(stored).isEqualTo(9999L);
    }

    @Test
    void updateWhatsAppConnectionId_rejectsInvalidSchemaName() {
        assertThatThrownBy(() ->
                nudgeRepository.updateWhatsAppConnectionId("bad-schema!", 1L, 42L))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schema name");
    }

    @Test
    void streamUsersWithNoUploadToday_returnsStoredWhatsappConnectionId() {
        int opId = insertUser("Op WA Stored", "911900000002", operatorTypeId);
        insertSchemeMapping(opId, schemeId, 1);
        nudgeRepository.updateWhatsAppConnectionId("tenant_test", opId, 1234L);

        List<Map<String, Object>> result = collectNoUploadToday("tenant_test");

        assertThat(result).hasSize(1);
        assertThat(((Number) result.get(0).get("whatsapp_connection_id")).longValue()).isEqualTo(1234L);
    }

    // ──────────────────────────── schema validation ────────────────────────────

    @Test
    void streamUsersWithNoUploadToday_rejectsInvalidSchemaName() {
        assertThatThrownBy(() -> nudgeRepository.streamUsersWithNoUploadToday("invalid-schema!", LocalDate.now(), row -> {}))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schema name");
    }

    @Test
    void streamUsersWithMissedDays_rejectsSqlInjectionAttempt() {
        assertThatThrownBy(() ->
                nudgeRepository.streamUsersWithMissedDays("'; DROP TABLE users; --", 3, LocalDate.now(), row -> {}))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schema name");
    }

    @Test
    void findOfficerByUserType_rejectsInvalidSchemaName() {
        assertThatThrownBy(() ->
                nudgeRepository.findOfficerByUserType("UPPER_CASE", 1, "OPERATOR"))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // ────────────────────────────── helpers ────────────────────────────────────

    private List<Map<String, Object>> collectNoUploadToday(String schema) {
        List<Map<String, Object>> result = new ArrayList<>();
        int count = nudgeRepository.streamUsersWithNoUploadToday(schema, LocalDate.now(), result::add);
        assertThat(count).isEqualTo(result.size());
        return result;
    }

    private List<Map<String, Object>> collectMissedDays(String schema, int minMissedDays) {
        List<Map<String, Object>> result = new ArrayList<>();
        int count = nudgeRepository.streamUsersWithMissedDays(schema, minMissedDays, LocalDate.now(), result::add);
        assertThat(count).isEqualTo(result.size());
        return result;
    }

    private int insertUser(String name, String phone, int userTypeId) {
        return insertUserWithLanguage(name, phone, userTypeId, 0);
    }

    private int insertUserWithLanguage(String name, String phone, int userTypeId, int languageId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tenant_test.user_table (title, phone_number, user_type, language_id, email) " +
                "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Integer.class, name, phone, userTypeId, languageId, phone + "@test.com");
    }

    private int insertInactiveUser(String name, String phone, int userTypeId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tenant_test.user_table (title, phone_number, user_type, language_id, email, status) " +
                "VALUES (?, ?, ?, 0, ?, 0) RETURNING id",
                Integer.class, name, phone, userTypeId, phone + "@test.com");
    }

    private void insertSchemeMapping(int userId, int schemeId, int status) {
        jdbcTemplate.update(
                "INSERT INTO tenant_test.user_scheme_mapping_table (user_id, scheme_id, status) VALUES (?, ?, ?)",
                userId, schemeId, status);
    }

    private void insertFlowReading(int schemeId, int createdBy, LocalDate date) {
        jdbcTemplate.update(
                "INSERT INTO tenant_test.flow_reading_table " +
                "(scheme_id, reading_date, created_by, updated_by) VALUES (?, ?, ?, ?)",
                schemeId, date, createdBy, createdBy);
    }
}
