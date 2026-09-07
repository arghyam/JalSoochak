package org.arghyam.jalsoochak.telemetry.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Write-path behaviour of {@link TelemetryTenantRepository}: the insert/update SQL it assembles per
 * tenant schema version, the placeholder/lenient-ingest provisioning, and the guards that make
 * tracking writes safe no-ops on schemas that predate a migration.
 */
@DisplayName("TelemetryTenantRepository — write paths")
class TelemetryTenantRepositoryWriteTest extends AbstractTelemetryTenantRepositoryTest {

    private static final LocalDateTime READING_AT = LocalDateTime.of(2026, 3, 1, 6, 30);

    @Nested
    @DisplayName("createFlowReading")
    class CreateFlowReading {

        @Test
        void usesPayloadJsonAndFlowVisionColumnsOnFullyMigratedSchema() {
            onColumnExists(true);
            onScalar("INSERT INTO", Number.class, 501L);

            Long id = repository.createFlowReading(SCHEMA, 7L, 2L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", "fv-1", "img", "reason");

            assertThat(id).isEqualTo(501L);
            String sql = capturedInsertSql();
            assertThat(sql).contains("payload_json").contains("flowvision_correlation_id");
        }

        @Test
        void omitsPayloadJsonOnLegacySchema() {
            onColumnExists(false);
            onScalar("INSERT INTO", Number.class, 502L);

            repository.createFlowReading(SCHEMA, 7L, 2L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", null, "img", "reason");

            String sql = capturedInsertSql();
            assertThat(sql).doesNotContain("payload_json").doesNotContain("flowvision_correlation_id");
            assertThat(sql).contains("reading_at");
        }

        @Test
        void usesPayloadJsonOnlyWhenFlowVisionColumnAbsent() {
            onColumnsExisting("payload_json", "observation_time");
            onScalar("INSERT INTO", Number.class, 503L);

            repository.createFlowReading(SCHEMA, 7L, 2L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", null, "img", "reason");

            String sql = capturedInsertSql();
            assertThat(sql).contains("payload_json")
                    .doesNotContain("flowvision_correlation_id")
                    .contains("observation_time");
        }

        @Test
        void usesFlowVisionColumnOnlyWhenPayloadJsonAbsent() {
            onColumnsExisting("flowvision_correlation_id");
            onScalar("INSERT INTO", Number.class, 504L);

            repository.createFlowReading(SCHEMA, 7L, 2L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", "fv-1", "img", "reason");

            String sql = capturedInsertSql();
            assertThat(sql).contains("flowvision_correlation_id").doesNotContain("payload_json");
        }

        @Test
        void prefersObservationTimeOverLegacyReadingAt() {
            onColumnsExisting("observation_time");
            onScalar("INSERT INTO", Number.class, 505L);

            repository.createFlowReading(SCHEMA, 7L, 2L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "c", "", null);

            assertThat(capturedInsertSql()).contains("observation_time");
        }

        @Test
        void substitutesEmptyStringForNullImageUrl() {
            onColumnExists(false);
            onScalar("INSERT INTO", Number.class, 506L);

            repository.createFlowReading(SCHEMA, 7L, 2L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "c", null, null);

            assertThat(capturedInsertArgs()).contains("");
        }

        @Test
        void returnsNullWhenInsertYieldsNoGeneratedId() {
            onColumnExists(false);
            onScalar("INSERT INTO", Number.class, null);

            assertThat(repository.createFlowReading(SCHEMA, 7L, 2L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "c", "", null)).isNull();
        }

        @Test
        void derivesReadingDateFromReadingTimestamp() {
            onColumnExists(false);
            onScalar("INSERT INTO", Number.class, 507L);

            repository.createFlowReading(SCHEMA, 7L, 2L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "c", "", null);

            assertThat(capturedInsertArgs()).contains(LocalDate.of(2026, 3, 1));
        }
    }

    @Nested
    @DisplayName("lenient ingest provisioning")
    class LenientIngest {

        @Test
        void getOrCreatePlaceholderSchemeReusesExistingPlaceholder() {
            onQuery("is_auto_provisioned = TRUE", row("id", 900L));

            assertThat(repository.getOrCreatePlaceholderScheme(SCHEMA, "S-1", "C-1")).isEqualTo(900L);
            Mockito.verify(jdbcTemplate, Mockito.never())
                    .queryForObject(ArgumentMatchers.contains("INSERT INTO"),
                            ArgumentMatchers.eq(Number.class), ArgumentMatchers.any(Object[].class));
        }

        @Test
        void getOrCreatePlaceholderSchemeInsertsInactiveAutoProvisionedRow() {
            onScalar("INSERT INTO", Number.class, 901L);

            assertThat(repository.getOrCreatePlaceholderScheme(SCHEMA, "S-1", "C-1")).isEqualTo(901L);
            assertThat(capturedInsertSql())
                    .contains("is_auto_provisioned")
                    .contains("TRUE, FALSE");
        }

        @Test
        void getOrCreatePlaceholderSchemeLabelsByStateIdWhenPresent() {
            onScalar("INSERT INTO", Number.class, 902L);

            repository.getOrCreatePlaceholderScheme(SCHEMA, " S-1 ", "C-1");

            assertThat(capturedInsertArgs())
                    .contains("Auto-provisioned scheme (state:S-1)");
        }

        @Test
        void getOrCreatePlaceholderSchemeLabelsByCentreIdWhenStateIdBlank() {
            onScalar("INSERT INTO", Number.class, 903L);

            repository.getOrCreatePlaceholderScheme(SCHEMA, null, "C-9");

            assertThat(capturedInsertArgs())
                    .contains("Auto-provisioned scheme (centre:C-9)");
        }

        @Test
        void getOrCreatePlaceholderSchemeRecoversFromInsertRace() {
            // Another request inserted the same placeholder between our SELECT and our INSERT: the
            // first lookup misses, the insert hits the unique constraint, the re-read finds their row.
            failInsertWithDuplicateKey();
            onQuerySequence("is_auto_provisioned = TRUE",
                    java.util.List.of(),
                    java.util.List.of(row("id", 904L)));

            assertThat(repository.getOrCreatePlaceholderScheme(SCHEMA, "S-1", "C-1")).isEqualTo(904L);
        }

        @Test
        void getOrCreatePlaceholderSchemeRethrowsWhenRaceLookupAlsoFindsNothing() {
            // The constraint violation was not a race, so there is nothing to recover to.
            failInsertWithDuplicateKey();

            assertThatThrownBy(() -> repository.getOrCreatePlaceholderScheme(SCHEMA, "S-1", "C-1"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void getOrCreateUnknownOperatorReusesExistingSentinel() {
            onQuery("is_auto_provisioned = TRUE", row("id", 910L));

            assertThat(repository.getOrCreateUnknownOperatorUserId(SCHEMA, 3)).isEqualTo(910L);
        }

        @Test
        void getOrCreateUnknownOperatorInsertsSentinelWithInvalidEmail() {
            onScalar("INSERT INTO", Number.class, 911L);

            assertThat(repository.getOrCreateUnknownOperatorUserId(SCHEMA, 3)).isEqualTo(911L);
            assertThat(capturedInsertArgs())
                    .contains("unknown-operator@auto.jalsoochak.invalid");
            assertThat(capturedInsertSql()).contains("'Unknown Operator'");
        }

        @Test
        void getOrCreateUnknownOperatorRecoversFromInsertRace() {
            failInsertWithDuplicateKey();
            onQuerySequence("is_auto_provisioned = TRUE",
                    java.util.List.of(),
                    java.util.List.of(row("id", 912L)));

            assertThat(repository.getOrCreateUnknownOperatorUserId(SCHEMA, 3)).isEqualTo(912L);
        }

        @Test
        void getOrCreateUnknownOperatorRethrowsWhenRaceLookupFindsNothing() {
            failInsertWithDuplicateKey();

            assertThatThrownBy(() -> repository.getOrCreateUnknownOperatorUserId(SCHEMA, 3))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /** Makes the RETURNING-id insert fail the way a concurrent duplicate insert would. */
        private void failInsertWithDuplicateKey() {
            Mockito.doThrow(new DataIntegrityViolationException("duplicate key"))
                    .when(jdbcTemplate)
                    .queryForObject(ArgumentMatchers.contains("INSERT INTO"),
                            ArgumentMatchers.eq(Number.class),
                            ArgumentMatchers.any(Object[].class));
        }
    }

    @Nested
    @DisplayName("ingestion tracking")
    class IngestionTracking {

        @Test
        void applyIngestionTrackingIsNoOpForNullReadingId() {
            onColumnExists(true);

            repository.applyIngestionTracking(SCHEMA, null, 3, "S-1", "C-1", "hash");

            Mockito.verify(jdbcTemplate, Mockito.never())
                    .update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class));
        }

        @Test
        void applyIngestionTrackingIsNoOpOnPreMigrationSchema() {
            onColumnExists(false);

            repository.applyIngestionTracking(SCHEMA, 5L, 3, "S-1", "C-1", "hash");

            Mockito.verify(jdbcTemplate, Mockito.never())
                    .update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class));
        }

        @Test
        void applyIngestionTrackingWritesBitmaskAndSubmittedIds() {
            onColumnExists(true);

            repository.applyIngestionTracking(SCHEMA, 5L, 3, " S-1 ", "C-1", "hash");

            assertThat(capturedUpdateSql()).contains("ingestion_source");
            assertThat(capturedUpdateArgs()).containsExactly(3, "S-1", "C-1", "hash", 5L);
        }

        @Test
        void applyIngestionTrackingNormalisesBlankSubmittedValuesToNull() {
            onColumnExists(true);

            repository.applyIngestionTracking(SCHEMA, 5L, 1, "  ", null, "");

            assertThat(capturedUpdateArgs()).containsExactly(1, null, null, null, 5L);
        }

        @Test
        void applyConfirmedReadingSourceIsNoOpOnPreMigrationSchema() {
            onColumnExists(false);

            repository.applyConfirmedReadingSource(SCHEMA, 5L, 2, "{}");

            Mockito.verify(jdbcTemplate, Mockito.never())
                    .update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class));
        }

        @Test
        void applyConfirmedReadingSourceIsNoOpForNullReadingId() {
            onColumnExists(true);

            repository.applyConfirmedReadingSource(SCHEMA, null, 2, "{}");

            Mockito.verify(jdbcTemplate, Mockito.never())
                    .update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class));
        }

        @Test
        void applyConfirmedReadingSourceWritesProvenanceOnly_whenAuditJsonBlank() {
            onColumnsExisting("confirmed_reading_source");

            repository.applyConfirmedReadingSource(SCHEMA, 5L, 2, "   ");

            assertThat(allUpdateSql()).hasSize(1);
            assertThat(allUpdateSql().get(0)).contains("confirmed_reading_source");
        }

        @Test
        void applyConfirmedReadingSourceFoldsAuditJsonIntoPayload() {
            onColumnsExisting("confirmed_reading_source", "payload_json");

            repository.applyConfirmedReadingSource(SCHEMA, 5L, 2, "{\"k\":1}");

            assertThat(allUpdateSql()).hasSize(2);
            assertThat(allUpdateSql().get(1)).contains("rollover_resolution");
        }

        @Test
        void applyConfirmedReadingSourceSwallowsAuditJsonFailure() {
            onColumnsExisting("confirmed_reading_source", "payload_json");
            Mockito.when(jdbcTemplate.update(ArgumentMatchers.contains("rollover_resolution"),
                            ArgumentMatchers.any(Object[].class)))
                    .thenThrow(new QueryTimeoutException("statement timeout"));

            // A malformed or slow audit write must never fail an otherwise successful submission.
            repository.applyConfirmedReadingSource(SCHEMA, 5L, 2, "{\"k\":1}");
        }
    }

    @Nested
    @DisplayName("scheme id mismatch reconciliation")
    class SchemeIdMismatch {

        @Test
        void isNoOpWhenOnlyOneSubmittedIdIsPresent() {
            repository.recordSchemeIdMismatchIfAny(SCHEMA, 5L, "S-1", null);
            repository.recordSchemeIdMismatchIfAny(SCHEMA, 5L, null, "C-1");
            repository.recordSchemeIdMismatchIfAny(SCHEMA, 5L, "  ", "C-1");

            Mockito.verify(jdbcTemplate, Mockito.never())
                    .update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class));
        }

        @Test
        void isNoOpWhenSchemeIdIsNull() {
            repository.recordSchemeIdMismatchIfAny(SCHEMA, null, "S-1", "C-1");

            Mockito.verify(jdbcTemplate, Mockito.never())
                    .update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class));
        }

        @Test
        void recordsBothSubmittedIdsAndSkipsAutoProvisionedSchemes() {
            repository.recordSchemeIdMismatchIfAny(SCHEMA, 5L, " S-1 ", " C-1 ");

            assertThat(capturedUpdateSql()).contains("is_auto_provisioned = FALSE");
            assertThat(capturedUpdateArgs())
                    .containsExactly("S-1", "S-1", "C-1", "C-1", 5L, "S-1", "S-1", "C-1", "C-1");
        }

        @Test
        void swallowsDatabaseFailureSoIngestionIsNeverBroken() {
            Mockito.when(jdbcTemplate.update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class)))
                    .thenThrow(new QueryTimeoutException("statement timeout"));

            repository.recordSchemeIdMismatchIfAny(SCHEMA, 5L, "S-1", "C-1");
        }
    }

    @Nested
    @DisplayName("persistFlowReadingWithTracking")
    class PersistWithTracking {

        @Test
        void insertsNewRowWhenNoPlaceholderExists() {
            onColumnExists(true);
            onScalar("INSERT INTO", Number.class, 601L);

            Long id = repository.persistFlowReadingWithTracking(SCHEMA, null, 7L, 2L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", "img", "reason",
                    3, "S-1", "C-1", "hash");

            assertThat(id).isEqualTo(601L);
            assertThat(allUpdateSql()).anySatisfy(sql -> assertThat(sql).contains("ingestion_source"));
        }

        @Test
        void updatesPlaceholderRowInPlaceWhenReadingIdSupplied() {
            onColumnExists(true);

            Long id = repository.persistFlowReadingWithTracking(SCHEMA, 42L, 7L, 2L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", "img", "reason",
                    3, "S-1", "C-1", "hash");

            assertThat(id).isEqualTo(42L);
            Mockito.verify(jdbcTemplate, Mockito.never())
                    .queryForObject(ArgumentMatchers.contains("INSERT INTO"),
                            ArgumentMatchers.eq(Number.class), ArgumentMatchers.any(Object[].class));
        }

        @Test
        void writesTheConfirmedReadingSourceAlongsideTheInsert() {
            // READING-PROVENANCE: the EXTERNALLY_ASSERTED marker is committed with the row, not after it.
            onColumnExists(true);
            onScalar("INSERT INTO", Number.class, 602L);

            repository.persistFlowReadingWithTracking(SCHEMA, null, 7L, 2L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", null, "img", "reason",
                    0, null, null, null, 3);

            assertThat(allUpdateSql()).anySatisfy(sql -> assertThat(sql).contains("confirmed_reading_source"));
        }

        @Test
        void skipsTheIngestionTrackingUpdateWhenThereIsNothingToTrack() {
            onColumnExists(true);
            onScalar("INSERT INTO", Number.class, 603L);

            repository.persistFlowReadingWithTracking(SCHEMA, null, 7L, 2L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", null, "img", "reason",
                    0, null, null, null, 3);

            assertThat(allUpdateSql()).noneSatisfy(sql -> assertThat(sql).contains("ingestion_source"));
        }
    }

    @Nested
    @DisplayName("pending meter-change and issue-report records")
    class PendingRecords {

        @Test
        void findLatestPendingMeterChangeRecordMapsProjection() {
            onQuery("meter_change_reason IS NOT NULL", row(
                    "id", 5L, "correlation_id", "meter-change-1",
                    "created_by", 2L, "extracted_reading", BigDecimal.ZERO));

            assertThat(repository.findLatestPendingMeterChangeRecord(SCHEMA, 7L, 2L))
                    .hasValueSatisfying(r -> assertThat(r.correlationId()).isEqualTo("meter-change-1"));
        }

        @Test
        void findLatestPendingIssueReportRecordMapsProjection() {
            onQuery("issue_report_reason IS NOT NULL", row(
                    "id", 6L, "correlation_id", "issue-report-1", "created_by", 2L));

            assertThat(repository.findLatestPendingIssueReportRecord(SCHEMA, 7L, 2L))
                    .hasValueSatisfying(r -> assertThat(r.id()).isEqualTo(6L));
        }

        @Test
        void findPendingMeterChangeRecordByCorrelationMapsProjection() {
            onQuery("correlation_id = ?", row(
                    "id", 7L, "correlation_id", "meter-change-2",
                    "created_by", 2L, "extracted_reading", BigDecimal.ZERO));

            assertThat(repository.findPendingMeterChangeRecordByCorrelation(
                    SCHEMA, 7L, 2L, "meter-change-2")).isPresent();
        }

        @Test
        void upsertPendingMeterChangeRecordUpdatesExistingAndKeepsCorrelationId() {
            onQuery("meter_change_reason IS NOT NULL", row(
                    "id", 5L, "correlation_id", "meter-change-1",
                    "created_by", 2L, "extracted_reading", BigDecimal.ZERO));

            String correlationId = repository.upsertPendingMeterChangeRecord(
                    SCHEMA, 7L, 2L, READING_AT, "Meter replaced");

            assertThat(correlationId).isEqualTo("meter-change-1");
            // The update, plus the cleanup that soft-deletes any other pending rows.
            assertThat(allUpdateSql()).hasSize(2);
            assertThat(allUpdateSql().get(1)).contains("deleted_at = NOW()");
        }

        @Test
        void upsertPendingMeterChangeRecordCreatesNewRecordWithGeneratedCorrelationId() {
            onScalar("INSERT INTO", Number.class, 610L);

            String correlationId = repository.upsertPendingMeterChangeRecord(
                    SCHEMA, 7L, 2L, READING_AT, "Meter replaced");

            assertThat(correlationId).startsWith("meter-change-");
        }

        @Test
        void upsertPendingIssueReportRecordUpdatesExisting() {
            onQuery("issue_report_reason IS NOT NULL", row(
                    "id", 6L, "correlation_id", "issue-report-1", "created_by", 2L));

            String correlationId = repository.upsertPendingIssueReportRecord(
                    SCHEMA, 7L, 2L, READING_AT, "No water");

            assertThat(correlationId).isEqualTo("issue-report-1");
            assertThat(capturedUpdateSql()).contains("issue_report_reason = ?");
        }

        @Test
        void upsertPendingIssueReportRecordCreatesNewRecord() {
            onScalar("INSERT INTO", Number.class, 611L);

            assertThat(repository.upsertPendingIssueReportRecord(
                    SCHEMA, 7L, 2L, READING_AT, "No water")).startsWith("issue-report-");
        }

        @Test
        void updatePendingMeterChangeReadingWritesPayloadJsonWhenAvailable() {
            onColumnsExisting("payload_json");

            repository.updatePendingMeterChangeReading(SCHEMA, 5L, new BigDecimal("99"), 2L);

            assertThat(capturedUpdateSql()).contains("payload_json");
            assertThat(capturedUpdateArgs()).hasSize(6);
        }

        @Test
        void updatePendingMeterChangeReadingOmitsPayloadJsonOnLegacySchema() {
            onColumnExists(false);

            repository.updatePendingMeterChangeReading(SCHEMA, 5L, new BigDecimal("99"), 2L);

            assertThat(capturedUpdateSql()).doesNotContain("payload_json");
            assertThat(capturedUpdateArgs()).hasSize(4);
        }

        @Test
        void updateMeterChangeReasonWritesReasonAndAuditColumns() {
            repository.updateMeterChangeReason(SCHEMA, 5L, "Meter replaced", 2L);

            assertThat(capturedUpdateSql()).contains("meter_change_reason = ?");
            assertThat(capturedUpdateArgs()).containsExactly("Meter replaced", 2L, 5L);
        }
    }

    @Nested
    @DisplayName("issue report records")
    class IssueReportRecords {

        @Test
        void createIssueReportRecordUpdatesSameDayRowWhenPresent() {
            onQuery("SELECT id", row("id", 700L));

            assertThat(repository.createIssueReportRecord(
                    SCHEMA, 7L, 2L, READING_AT, "corr", "No water")).isEqualTo(700L);
            assertThat(capturedUpdateSql()).contains("issue_report_reason = ?");
        }

        @Test
        void createIssueReportRecordInsertsWhenNoSameDayRow() {
            onScalar("INSERT INTO", Number.class, 701L);

            assertThat(repository.createIssueReportRecord(
                    SCHEMA, 7L, 2L, READING_AT, "corr", "No water")).isEqualTo(701L);
        }

        @Test
        void createMeterChangeReasonRecordInsertsWithPayloadJsonWhenAvailable() {
            onColumnsExisting("payload_json");
            onScalar("INSERT INTO", Number.class, 702L);

            assertThat(repository.createMeterChangeReasonRecord(
                    SCHEMA, 7L, 2L, READING_AT, "corr", "Meter replaced")).isEqualTo(702L);
            assertThat(capturedInsertSql()).contains("payload_json");
        }

        @Test
        void createMeterChangeReasonRecordInsertsWithoutPayloadJsonOnLegacySchema() {
            onColumnExists(false);
            onScalar("INSERT INTO", Number.class, 703L);

            repository.createMeterChangeReasonRecord(SCHEMA, 7L, 2L, READING_AT, "corr", "Meter replaced");

            assertThat(capturedInsertSql()).doesNotContain("payload_json");
        }
    }

    @Nested
    @DisplayName("scheme selection records")
    class SchemeSelection {

        @Test
        void findLatestPendingSchemeSelectionForDateMapsProjection() {
            onQuery("correlation_id LIKE ?", row(
                    "id", 5L, "scheme_id", 7L, "correlation_id", "scheme-selection-abc"));

            assertThat(repository.findLatestPendingSchemeSelectionForDate(
                    SCHEMA, 2L, LocalDate.of(2026, 3, 1)))
                    .hasValueSatisfying(r -> assertThat(r.schemeId()).isEqualTo(7L));
        }

        @Test
        void upsertPendingSchemeSelectionUpdatesExistingRecord() {
            onQuery("correlation_id LIKE ?", row(
                    "id", 5L, "scheme_id", 7L, "correlation_id", "scheme-selection-abc"));

            assertThat(repository.upsertPendingSchemeSelectionRecord(SCHEMA, 9L, 2L, READING_AT))
                    .isEqualTo("scheme-selection-abc");
            assertThat(capturedUpdateSql()).contains("scheme_id = ?");
        }

        @Test
        void upsertPendingSchemeSelectionCreatesZeroValuedPlaceholderReading() {
            onScalar("INSERT INTO", Number.class, 800L);

            String correlationId =
                    repository.upsertPendingSchemeSelectionRecord(SCHEMA, 9L, 2L, READING_AT);

            assertThat(correlationId).startsWith("scheme-selection-");
            assertThat(capturedInsertArgs()).contains(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("reading value updates")
    class ReadingUpdates {

        @Test
        void updateFlowReadingChannelIsNoOpForNullReadingId() {
            repository.updateFlowReadingChannel(SCHEMA, null, "BFM");

            Mockito.verify(jdbcTemplate, Mockito.never())
                    .update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class));
        }

        @Test
        void updateFlowReadingChannelWritesShortCode() {
            repository.updateFlowReadingChannel(SCHEMA, 5L, "BFM");

            assertThat(capturedUpdateArgs()).containsExactly("BFM", 5L);
        }

        @Test
        void touchLatestAnomalyByTypeForTodayReturnsAffectedRowCount() {
            Mockito.when(jdbcTemplate.update(ArgumentMatchers.contains("anomaly_table"),
                    ArgumentMatchers.any(Object[].class))).thenReturn(1);

            assertThat(repository.touchLatestAnomalyByTypeForToday(SCHEMA, 1L, 2L, 3)).isEqualTo(1);
        }

        @Test
        void createTenantAnomalyRecordWritesBothReasonAndDetailWhenAvailable() {
            onColumnsExisting("detail", "reason");

            repository.createTenantAnomalyRecord(SCHEMA, 1L, 2L, 3, "Rollover", 0);

            assertThat(capturedUpdateSql()).contains("reason, detail");
            assertThat(capturedUpdateArgs()).containsExactly(1L, 2L, 3, "Rollover", "Rollover", 0);
        }

        @Test
        void createTenantAnomalyRecordWritesDetailOnlyWhenReasonColumnAbsent() {
            onColumnsExisting("detail");

            repository.createTenantAnomalyRecord(SCHEMA, 1L, 2L, 3, "Rollover", 0);

            assertThat(capturedUpdateSql()).contains("type, detail");
            assertThat(capturedUpdateArgs()).containsExactly(1L, 2L, 3, "Rollover", 0);
        }

        @Test
        void createTenantAnomalyRecordFallsBackToReasonColumn() {
            onColumnExists(false);

            repository.createTenantAnomalyRecord(SCHEMA, 1L, 2L, 3, "Rollover", 0);

            assertThat(capturedUpdateSql()).contains("type, reason");
        }
    }
}
