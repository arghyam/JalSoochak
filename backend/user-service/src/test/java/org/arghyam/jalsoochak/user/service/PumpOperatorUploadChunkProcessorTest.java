package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.event.UserAnalyticsEventPublisher;
import org.arghyam.jalsoochak.user.event.UserEventPublisher;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.repository.UserUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PumpOperatorUploadChunkProcessor")
class PumpOperatorUploadChunkProcessorTest {

    @Mock private UserTenantRepository userTenantRepository;
    @Mock private UserUploadRepository userUploadRepository;
    @Mock private UserEventPublisher userEventPublisher;
    @Mock private UserAnalyticsEventPublisher userAnalyticsEventPublisher;

    private PumpOperatorUploadChunkProcessor processor;

    private static final String SCHEMA = "tenant_mp";
    private static final String TENANT_CODE = "MP";

    private static final TenantUserRecord ACTOR = new TenantUserRecord(
            1L, 10, "919000000001", "admin@test.com", 2L, "STATE_ADMIN", "Admin", null, 1, null);

    @BeforeEach
    void setUp() {
        processor = new PumpOperatorUploadChunkProcessor(
                userTenantRepository, userUploadRepository,
                userEventPublisher, userAnalyticsEventPublisher);
    }

    private Map<String, Integer> userTypeIds() {
        Map<String, Integer> map = new HashMap<>();
        map.put("pump_operator", 4);
        map.put("section_officer", 3);
        map.put("sub_divisional_officer", 5);
        return map;
    }

    private Map<String, Integer> schemeCache(String stateSchemeId, int schemeId) {
        Map<String, Integer> map = new HashMap<>();
        map.put(stateSchemeId, schemeId);
        return map;
    }

    private PumpOperatorUploadChunkProcessor.UploadRow row(
            int rowNum, String firstName, String lastName, String fullName,
            String phone, String personType, String stateSchemeId) {
        return new PumpOperatorUploadChunkProcessor.UploadRow(
                rowNum, firstName, lastName, fullName, phone, personType, stateSchemeId);
    }

    // ── empty input ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("empty input")
    class EmptyInput {

        @Test
        @DisplayName("returns zero counts for null rows")
        void returnsZerosForNull() {
            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1, null,
                            new HashMap<>(), new HashSet<>());
            assertThat(result.uploadedRows()).isZero();
            assertThat(result.skippedRows()).isZero();
            assertThat(result.unchangedRows()).isZero();
        }

        @Test
        @DisplayName("returns zero counts for empty row list")
        void returnsZerosForEmptyList() {
            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1, List.of(),
                            new HashMap<>(), new HashSet<>());
            assertThat(result.uploadedRows()).isZero();
            assertThat(result.skippedRows()).isZero();
        }
    }

    // ── new user creation ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("new user creation")
    class NewUserCreation {

        @Test
        @DisplayName("creates new user and inserts scheme mapping")
        void createsNewUserAndMapping() {
            var uploadRow = row(1, "Ram", "Kumar", "Ram Kumar", "9876543210", "pump_operator", "SS-1");
            Map<String, Integer> schemeCache = schemeCache("SS-1", 100);

            when(userTenantRepository.findUserByPhone(SCHEMA, "919876543210")).thenReturn(Optional.empty());
            when(userTenantRepository.findUserByEmail(eq(SCHEMA), anyString())).thenReturn(Optional.empty());
            when(userTenantRepository.createUser(eq(SCHEMA), anyString(), eq(10), anyString(),
                    anyString(), eq(4), eq("919876543210"), anyString(), eq(1L))).thenReturn(42L);
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{1});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(uploadRow), schemeCache, new HashSet<>());

            assertThat(result.uploadedRows()).isEqualTo(1);
            assertThat(result.skippedRows()).isZero();
            verify(userTenantRepository).createUser(eq(SCHEMA), anyString(), eq(10), anyString(),
                    anyString(), eq(4), eq("919876543210"), anyString(), eq(1L));
        }

        @Test
        @DisplayName("uses full_name when provided")
        void usesFullNameWhenProvided() {
            var uploadRow = row(1, "", "", "Sita Devi", "9876543211", "pump_operator", "SS-1");
            Map<String, Integer> schemeCache = schemeCache("SS-1", 100);

            when(userTenantRepository.findUserByPhone(SCHEMA, "919876543211")).thenReturn(Optional.empty());
            when(userTenantRepository.findUserByEmail(eq(SCHEMA), anyString())).thenReturn(Optional.empty());
            when(userTenantRepository.createUser(eq(SCHEMA), anyString(), eq(10), eq("Sita Devi"),
                    anyString(), eq(4), eq("919876543211"), anyString(), eq(1L))).thenReturn(43L);
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{1});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(uploadRow), schemeCache, new HashSet<>());

            assertThat(result.uploadedRows()).isEqualTo(1);
            verify(userTenantRepository).createUser(eq(SCHEMA), anyString(), eq(10), eq("Sita Devi"),
                    anyString(), eq(4), eq("919876543211"), anyString(), eq(1L));
        }

        @Test
        @DisplayName("resolves scheme from repository when not in cache")
        void resolvesSchemeFromRepository() {
            var uploadRow = row(1, "Raj", "", "", "9876543212", "pump_operator", "SS-NOTCACHED");

            when(userUploadRepository.findSchemeId(SCHEMA, "SS-NOTCACHED", null)).thenReturn(200);
            when(userTenantRepository.findUserByPhone(SCHEMA, "919876543212")).thenReturn(Optional.empty());
            when(userTenantRepository.findUserByEmail(eq(SCHEMA), anyString())).thenReturn(Optional.empty());
            when(userTenantRepository.createUser(eq(SCHEMA), anyString(), eq(10), anyString(),
                    anyString(), eq(4), eq("919876543212"), anyString(), eq(1L))).thenReturn(44L);
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{1});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            Map<String, Integer> emptyCache = new HashMap<>();
            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(uploadRow), emptyCache, new HashSet<>());

            assertThat(result.uploadedRows()).isEqualTo(1);
            verify(userUploadRepository).findSchemeId(SCHEMA, "SS-NOTCACHED", null);
        }

        @Test
        @DisplayName("handles createUser returning null by skipping row")
        void skipsRowWhenCreateUserReturnsNull() {
            var uploadRow = row(1, "Mohan", "", "", "9876543213", "pump_operator", "SS-1");
            Map<String, Integer> schemeCache = schemeCache("SS-1", 100);

            when(userTenantRepository.findUserByPhone(SCHEMA, "919876543213")).thenReturn(Optional.empty());
            when(userTenantRepository.findUserByEmail(eq(SCHEMA), anyString())).thenReturn(Optional.empty());
            when(userTenantRepository.createUser(any(), anyString(), anyInt(), anyString(),
                    anyString(), anyInt(), anyString(), anyString(), anyLong())).thenReturn(null);
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(uploadRow), schemeCache, new HashSet<>());

            assertThat(result.uploadedRows()).isZero();
            assertThat(result.skippedRows()).isEqualTo(1);
        }
    }

    // ── existing user update ──────────────────────────────────────────────────

    @Nested
    @DisplayName("existing user update")
    class ExistingUserUpdate {

        @Test
        @DisplayName("updates existing pump_operator and inserts scheme mapping")
        void updatesExistingPumpOperator() {
            TenantUserRecord existingUser = new TenantUserRecord(
                    55L, 10, "919876543214", "po@test.com", 4L, "PUMP_OPERATOR",
                    "Old Name", "kc-uuid-123", 1, null);
            var uploadRow = row(1, "New", "Name", "New Name", "9876543214", "pump_operator", "SS-1");
            Map<String, Integer> schemeCache = schemeCache("SS-1", 100);

            when(userTenantRepository.findUserByPhone(SCHEMA, "919876543214"))
                    .thenReturn(Optional.of(existingUser));
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{1});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(uploadRow), schemeCache, new HashSet<>());

            assertThat(result.uploadedRows()).isEqualTo(1);
            verify(userTenantRepository).updateUserProfile(SCHEMA, 55L, "New Name", "919876543214");
            verify(userTenantRepository).updateUserLanguageId(SCHEMA, 55L, 1);
        }

        @Test
        @DisplayName("skips existing user whose type doesn't match the requested type")
        void skipsExistingUserWithDifferentType() {
            TenantUserRecord existingUser = new TenantUserRecord(
                    56L, 10, "919876543215", "so@test.com", 3L, "SECTION_OFFICER",
                    "Officer", null, 1, null);
            var uploadRow = row(1, "Ram", "", "", "9876543215", "pump_operator", "SS-1");
            Map<String, Integer> schemeCache = schemeCache("SS-1", 100);

            when(userTenantRepository.findUserByPhone(SCHEMA, "919876543215"))
                    .thenReturn(Optional.of(existingUser));
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(uploadRow), schemeCache, new HashSet<>());

            assertThat(result.skippedRows()).isEqualTo(1);
            verify(userTenantRepository, never()).updateUserProfile(any(), anyLong(), anyString(), anyString());
        }
    }

    // ── skipped rows ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("skipped rows")
    class SkippedRows {

        @Test
        @DisplayName("skips row when person type is unknown")
        void skipsRowForUnknownPersonType() {
            var uploadRow = row(1, "X", "", "", "9876543216", "unknown_role", "SS-1");
            Map<String, Integer> schemeCache = schemeCache("SS-1", 100);

            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(uploadRow), schemeCache, new HashSet<>());

            assertThat(result.skippedRows()).isEqualTo(1);
            verify(userTenantRepository, never()).findUserByPhone(any(), any());
        }

        @Test
        @DisplayName("skips row when scheme is not found in cache or repository")
        void skipsRowForMissingScheme() {
            var uploadRow = row(1, "Y", "", "", "9876543217", "pump_operator", "SS-MISSING");

            when(userUploadRepository.findSchemeId(SCHEMA, "SS-MISSING", null)).thenReturn(null);
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(uploadRow), new HashMap<>(), new HashSet<>());

            assertThat(result.skippedRows()).isEqualTo(1);
            verify(userTenantRepository, never()).findUserByPhone(any(), any());
        }

        @Test
        @DisplayName("handles exception per row gracefully and continues processing")
        void handlesExceptionPerRowGracefully() {
            var goodRow = row(1, "Valid", "", "", "9876543218", "pump_operator", "SS-1");
            var badRow = row(2, "Bad", "", "", "INVALID-PHONE-!!!", "pump_operator", "SS-1");
            Map<String, Integer> schemeCache = schemeCache("SS-1", 100);

            // Good row: new user created successfully
            when(userTenantRepository.findUserByPhone(SCHEMA, "919876543218")).thenReturn(Optional.empty());
            when(userTenantRepository.findUserByEmail(eq(SCHEMA), anyString())).thenReturn(Optional.empty());
            when(userTenantRepository.createUser(eq(SCHEMA), anyString(), eq(10), anyString(),
                    anyString(), eq(4), anyString(), anyString(), eq(1L))).thenReturn(99L);
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{1});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(goodRow, badRow), schemeCache, new HashSet<>());

            // good row uploaded, bad row skipped
            assertThat(result.uploadedRows() + result.skippedRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("section_officer type is uploaded using correct type ID")
        void uploadsSectionOfficerWithCorrectTypeId() {
            var uploadRow = row(1, "Suresh", "", "", "9876543219", "section_officer", "SS-1");
            Map<String, Integer> schemeCache = schemeCache("SS-1", 100);

            when(userTenantRepository.findUserByPhone(SCHEMA, "919876543219")).thenReturn(Optional.empty());
            when(userTenantRepository.findUserByEmail(eq(SCHEMA), anyString())).thenReturn(Optional.empty());
            when(userTenantRepository.createUser(eq(SCHEMA), anyString(), eq(10), anyString(),
                    anyString(), eq(3), eq("919876543219"), anyString(), eq(1L))).thenReturn(77L);
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{1});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            PumpOperatorUploadChunkProcessor.ChunkResult result =
                    processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                            List.of(uploadRow), schemeCache, new HashSet<>());

            assertThat(result.uploadedRows()).isEqualTo(1);
            // Section officers use type ID 3
            verify(userTenantRepository).createUser(eq(SCHEMA), anyString(), eq(10), anyString(),
                    anyString(), eq(3), eq("919876543219"), anyString(), eq(1L));
        }
    }

    // ── mappingsClearedInUpload deduplication ─────────────────────────────────

    @Nested
    @DisplayName("mappingsClearedInUpload deduplication")
    class MappingsClearedDeduplication {

        @Test
        @DisplayName("marks mappings deleted only once per user across multiple rows")
        void marksDeletedOnlyOncePerUser() {
            TenantUserRecord existingUser = new TenantUserRecord(
                    88L, 10, "919876543220", "po2@test.com", 4L, "PUMP_OPERATOR",
                    "PO Two Schemes", null, 1, null);

            var row1 = row(1, "", "", "", "9876543220", "pump_operator", "SS-A");
            var row2 = row(2, "", "", "", "9876543220", "pump_operator", "SS-B");
            Map<String, Integer> schemeCache = new HashMap<>();
            schemeCache.put("SS-A", 101);
            schemeCache.put("SS-B", 102);
            Set<Long> cleared = new HashSet<>();

            when(userTenantRepository.findUserByPhone(eq(SCHEMA), eq("919876543220")))
                    .thenReturn(Optional.of(existingUser));
            when(userUploadRepository.insertUserSchemeMappings(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(new int[]{1, 1});
            when(userUploadRepository.markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt()))
                    .thenReturn(0);

            processor.processChunk(SCHEMA, TENANT_CODE, ACTOR, userTypeIds(), 1, 1,
                    List.of(row1, row2), schemeCache, cleared);

            // markUserSchemeMappingsDeleted should be called once (for user 88L's first appearance)
            verify(userUploadRepository).markUserSchemeMappingsDeleted(eq(SCHEMA), anyList(), anyInt());
            // User should be in the cleared set after processing
            assertThat(cleared).contains(88L);
        }
    }
}
