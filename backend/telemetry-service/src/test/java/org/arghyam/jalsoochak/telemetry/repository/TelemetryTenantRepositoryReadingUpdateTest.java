package org.arghyam.jalsoochak.telemetry.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading correction, location capture, and the placeholder-row lookup used by lenient ingest.
 */
@DisplayName("TelemetryTenantRepository — reading updates")
class TelemetryTenantRepositoryReadingUpdateTest extends AbstractTelemetryTenantRepositoryTest {

    private static final LocalDateTime READING_AT = LocalDateTime.of(2026, 3, 1, 6, 30);
    private static final LocalDate DAY = LocalDate.of(2026, 3, 1);

    @Nested
    @DisplayName("updateReadingValues")
    class UpdateReadingValues {

        @Test
        void writesPayloadJsonWhenTheColumnExists() {
            onColumnsExisting("payload_json");

            repository.updateReadingValues(SCHEMA, 5L, new BigDecimal("1234"), 2L);

            assertThat(capturedUpdateSql()).contains("payload_json");
            assertThat(capturedUpdateArgs()).containsExactly(
                    new BigDecimal("1234"), new BigDecimal("1234"),
                    new BigDecimal("1234"), new BigDecimal("1234"), 2L, 5L);
        }

        @Test
        void omitsPayloadJsonOnALegacySchema() {
            onColumnExists(false);

            repository.updateReadingValues(SCHEMA, 5L, new BigDecimal("1234"), 2L);

            assertThat(capturedUpdateSql()).doesNotContain("payload_json");
            assertThat(capturedUpdateArgs())
                    .containsExactly(new BigDecimal("1234"), new BigDecimal("1234"), 2L, 5L);
        }
    }

    @Nested
    @DisplayName("updateConfirmedReading")
    class UpdateConfirmedReading {

        @Test
        void writesTheConfirmedValueAndPayloadJson() {
            onColumnsExisting("payload_json");

            repository.updateConfirmedReading(SCHEMA, 5L, new BigDecimal("1234"), 2L);

            assertThat(capturedUpdateSql()).contains("payload_json");
            assertThat(capturedUpdateArgs())
                    .containsExactly(new BigDecimal("1234"), new BigDecimal("1234"), 2L, 5L);
        }

        @Test
        void omitsPayloadJsonOnALegacySchema() {
            onColumnExists(false);

            repository.updateConfirmedReading(SCHEMA, 5L, new BigDecimal("1234"), 2L);

            assertThat(capturedUpdateArgs()).containsExactly(new BigDecimal("1234"), 2L, 5L);
        }

        @Test
        void foldsProvenanceIntoTheSameStatementRatherThanASecondRoundTrip() {
            onColumnsExisting("payload_json", "confirmed_reading_source");

            repository.updateConfirmedReading(SCHEMA, 5L, new BigDecimal("1234"), 2L, 3);

            assertThat(allUpdateSql()).hasSize(1);
            assertThat(capturedUpdateSql()).contains("confirmed_reading_source = ?");
            assertThat(capturedUpdateArgs())
                    .containsExactly(new BigDecimal("1234"), new BigDecimal("1234"), 3, 2L, 5L);
        }

        @Test
        void leavesProvenanceUntouchedWhenNoSourceIsGiven() {
            onColumnsExisting("payload_json", "confirmed_reading_source");

            repository.updateConfirmedReading(SCHEMA, 5L, new BigDecimal("1234"), 2L, null);

            assertThat(capturedUpdateSql()).doesNotContain("confirmed_reading_source");
        }

        @Test
        void skipsProvenanceOnAPreMigrationSchemaWithoutFailing() {
            onColumnsExisting("payload_json");

            repository.updateConfirmedReading(SCHEMA, 5L, new BigDecimal("1234"), 2L, 3);

            assertThat(capturedUpdateSql()).doesNotContain("confirmed_reading_source");
            assertThat(capturedUpdateArgs())
                    .containsExactly(new BigDecimal("1234"), new BigDecimal("1234"), 2L, 5L);
        }

        @Test
        void writesTheSubmittedUnitInTheSameStatement() {
            onColumnsExisting("payload_json", "confirmed_reading_source", "submitted_unit");

            repository.updateConfirmedReading(SCHEMA, 5L, new BigDecimal("1.5"), 2L, 3, "L");

            assertThat(allUpdateSql()).hasSize(1);
            assertThat(capturedUpdateSql()).contains("confirmed_reading_source = ?, submitted_unit = ?");
            assertThat(capturedUpdateArgs())
                    .containsExactly(new BigDecimal("1.5"), new BigDecimal("1.5"), 3, "L", 2L, 5L);
        }

        @Test
        void leavesTheSubmittedUnitUntouchedWhenNoneIsGiven() {
            onColumnsExisting("payload_json", "submitted_unit");

            repository.updateConfirmedReading(SCHEMA, 5L, new BigDecimal("1234"), 2L, null, null);

            assertThat(capturedUpdateSql()).doesNotContain("submitted_unit");
        }

        @Test
        void dropsTheSubmittedUnitOnAPreV56Schema() {
            onColumnsExisting("payload_json");

            repository.updateConfirmedReading(SCHEMA, 5L, new BigDecimal("1234"), 2L, null, "m3");

            assertThat(capturedUpdateSql()).doesNotContain("submitted_unit");
            assertThat(capturedUpdateArgs())
                    .containsExactly(new BigDecimal("1234"), new BigDecimal("1234"), 2L, 5L);
        }
    }

    @Nested
    @DisplayName("updateReadingLocation")
    class UpdateReadingLocation {

        @Test
        void writesBothCoordinates() {
            onColumnsExisting("latitude", "longitude");

            repository.updateReadingLocation(SCHEMA, 5L, new BigDecimal("26.1"), new BigDecimal("91.7"), 2L);

            assertThat(capturedUpdateArgs())
                    .containsExactly(new BigDecimal("26.1"), new BigDecimal("91.7"), 2L, 5L);
        }

        @Test
        void failsFastWhenTheLatitudeColumnIsMissing() {
            onColumnExists(false);

            assertThatThrownBy(() -> repository.updateReadingLocation(
                    SCHEMA, 5L, BigDecimal.ONE, BigDecimal.ONE, 2L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("latitude");
        }

        @Test
        void failsFastWhenOnlyTheLongitudeColumnIsMissing() {
            onColumnsExisting("latitude");

            assertThatThrownBy(() -> repository.updateReadingLocation(
                    SCHEMA, 5L, BigDecimal.ONE, BigDecimal.ONE, 2L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("longitude");
        }
    }

    @Nested
    @DisplayName("updateSchemeChannel")
    class UpdateSchemeChannel {

        @Test
        void writesTheChannelWhenTheColumnExists() {
            onColumnsExisting("channel");

            repository.updateSchemeChannel(SCHEMA, 7L, 2);

            assertThat(capturedUpdateArgs()).containsExactly(2, 7L);
        }

        @Test
        void failsFastWhenTheColumnIsMissing() {
            onColumnExists(false);

            assertThatThrownBy(() -> repository.updateSchemeChannel(SCHEMA, 7L, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("channel");
        }
    }

    @Nested
    @DisplayName("updateFlowReadingFromIngestion")
    class UpdateFromIngestion {

        @Test
        void writesPayloadJsonAndOcrCorrelationIdOnAFullyMigratedSchema() {
            onColumnExists(true);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", "ocr-1", "img", "reason", 2L);

            assertThat(allQuerySql().get(0))
                    .contains("payload_json")
                    .contains("ocr_correlation_id = COALESCE(?, ocr_correlation_id)")
                    .contains("observation_time")
                    .contains("updated_at = clock_timestamp()")
                    .contains("RETURNING updated_at");
            // The legacy overload passes no channel or unit, which leaves both columns as they are.
            assertThat(lastQueryArgs()).containsExactly(
                    READING_AT, DAY, new BigDecimal("10"), new BigDecimal("11"),
                    new BigDecimal("11"), new BigDecimal("10"), "corr-1", "ocr-1",
                    null, null, "img", "reason", 2L, 5L);
        }

        @Test
        void omitsBothOptionalColumnsOnALegacySchema() {
            onColumnExists(false);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", null, "img", "reason", 2L);

            assertThat(allQuerySql().get(0))
                    .doesNotContain("payload_json")
                    .doesNotContain("_correlation_id = COALESCE")
                    .contains("reading_at");
            assertThat(lastQueryArgs()).containsExactly(
                    READING_AT, DAY, new BigDecimal("10"), new BigDecimal("11"),
                    "corr-1", null, "img", "reason", 2L, 5L);
        }

        @Test
        void writesPayloadJsonOnlyWhenTheOcrCorrelationColumnIsAbsent() {
            onColumnsExisting("payload_json");

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", null, "img", "reason", 2L);

            assertThat(allQuerySql().get(0)).contains("payload_json").doesNotContain("_correlation_id = COALESCE");
            assertThat(lastQueryArgs()).hasSize(12);
        }

        @Test
        void writesTheOcrCorrelationIdOnlyWhenPayloadJsonIsAbsent() {
            onColumnsExisting("ocr_correlation_id");

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", "ocr-1", "img", "reason", 2L);

            assertThat(allQuerySql().get(0)).contains("ocr_correlation_id").doesNotContain("payload_json");
            assertThat(lastQueryArgs()).hasSize(11);
        }

        @Test
        void onlyOverwritesAPlaceholderCorrelationId() {
            onColumnExists(false);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "corr-1", null, "img", null, 2L);

            // A real correlation id already on the row must survive; only the scheme-selection
            // placeholder (or an empty value) is replaced.
            assertThat(allQuerySql().get(0))
                    .contains("correlation_id LIKE 'scheme-selection-%'")
                    .contains("ELSE correlation_id");
        }

        @Test
        void substitutesEmptyStringForANullImageUrl() {
            onColumnExists(false);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "corr-1", null, null, null, 2L);

            assertThat(lastQueryArgs()).contains("");
        }

        /**
         * A reading written onto a placeholder takes its channel and unit, and the version the update
         * wrote comes back with the row id.
         */
        @Test
        void writesTheChannelAndSubmittedUnitAndReturnsTheVersion() {
            onColumnExists(true);
            LocalDateTime updatedAt = LocalDateTime.of(2026, 3, 1, 6, 30, 9, 1_000);
            onQuery("RETURNING updated_at", row("updated_at", updatedAt));

            FlowReadingVersion version = repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    BigDecimal.ZERO, new BigDecimal("90"), "corr-1", null, "", null, 2L, "PDU", "min");

            assertThat(version).isEqualTo(new FlowReadingVersion(5L, updatedAt));
            assertThat(allQuerySql().get(0))
                    .contains("channel = COALESCE(?, channel)")
                    .contains("submitted_unit = COALESCE(?, submitted_unit)");
            assertThat(lastQueryArgs()).containsSequence("PDU", "min");
        }

        /** The row vanished between the placeholder lookup and this write: no version to publish. */
        @Test
        void returnsNoVersionWhenTheRowIsGone() {
            onColumnExists(true);

            FlowReadingVersion version = repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    BigDecimal.ZERO, new BigDecimal("90"), "corr-1", null, "", null, 2L, "BFM", "m3");

            assertThat(version).isEqualTo(new FlowReadingVersion(5L, null));
        }

        @Test
        void nineArgumentOverloadPassesNoOcrCorrelationId() {
            onColumnExists(true);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "corr-1", "img", "reason", 2L);

            assertThat(lastQueryArgs()).containsSequence("corr-1", null);
        }
    }

    @Nested
    @DisplayName("findLatestPlaceholderFlowReadingIdForDate")
    class PlaceholderLookup {

        @Test
        void matchesOnlyRowsWithNoReadingNoReasonAndNoImage() {
            onQuery("COALESCE(image_url, '') = ''", row("id", 55L));

            assertThat(repository.findLatestPlaceholderFlowReadingIdForDate(SCHEMA, 7L, 2L, DAY))
                    .contains(55L);
            assertThat(allQuerySql()).anySatisfy(sql -> assertThat(sql)
                    .contains("COALESCE(extracted_reading, 0) = 0")
                    .contains("COALESCE(confirmed_reading, 0) = 0")
                    .contains("meter_change_reason IS NULL")
                    .contains("issue_report_reason IS NULL"));
        }

        @Test
        void isEmptyWhenNoPlaceholderExistsForTheDay() {
            assertThat(repository.findLatestPlaceholderFlowReadingIdForDate(SCHEMA, 7L, 2L, DAY)).isEmpty();
        }
    }

    /**
     * "A completed submission" means a reading value was recorded — that is {@code confirmed_reading > 0}
     * and nothing else. Pairing it with {@code extracted_reading > 0} added no filtering (every row that
     * is not a real submission — scheme-selection placeholder, location row, meter-change record, issue
     * report — carries confirmed_reading = 0 and is already excluded) while silently hiding every row
     * whose value was not extracted from a photo: a hand-typed manual reading, and an API submission
     * carrying confirmed_reading. Both store the 0 sentinel in extracted_reading, so the operator's
     * "update previous day's reading" flow could not target the days they cover.
     */
    @Nested
    @DisplayName("completed-submission filter")
    class CompletedSubmissionFilter {

        private void assertCompletedFilter(Runnable call) {
            call.run();
            assertThat(allQuerySql()).anySatisfy(sql -> assertThat(sql)
                    .contains("confirmed_reading > 0")
                    .doesNotContain("extracted_reading > 0"));
        }

        @Test
        void findLatestCompletedFlowReadingBeforeDateAcceptsRowsWithNoExtractedValue() {
            assertCompletedFilter(() ->
                    repository.findLatestCompletedFlowReadingBeforeDate(SCHEMA, 7L, 2L, DAY));
        }

        @Test
        void findLatestCompletedFlowReadingOnDateForUserAcceptsRowsWithNoExtractedValue() {
            assertCompletedFilter(() ->
                    repository.findLatestCompletedFlowReadingOnDateForUser(SCHEMA, 7L, 2L, DAY));
        }

        @Test
        void findLatestCompletedReadingForTodayAcceptsRowsWithNoExtractedValue() {
            assertCompletedFilter(() ->
                    repository.findLatestCompletedReadingForToday(SCHEMA, 7L, 2L));
        }

        @Test
        void findLatestCompletedReadingForPreviousDayAcceptsRowsWithNoExtractedValue() {
            assertCompletedFilter(() ->
                    repository.findLatestCompletedReadingForPreviousDay(SCHEMA, 7L, 2L));
        }
    }

    /**
     * The scheme-scoped finders back the officer correction API, which picks the row to correct with
     * them. They are named "completed" but had no completion predicate at all, so a scheme-selection
     * placeholder, a location row, a meter-change record or a standalone issue report — every one of
     * them confirmed_reading = 0 — could be returned as the reading to correct.
     */
    @Nested
    @DisplayName("scheme-level completion filter")
    class SchemeLevelCompletionFilter {

        private void assertCompletionFilter(Runnable call) {
            call.run();
            assertThat(allQuerySql()).anySatisfy(sql -> assertThat(sql).contains("confirmed_reading > 0"));
        }

        @Test
        void findLatestCompletedFlowReadingForSchemeExcludesZeroConfirmedRows() {
            assertCompletionFilter(() -> repository.findLatestCompletedFlowReadingForScheme(SCHEMA, 7L));
        }

        @Test
        void findLatestCompletedFlowReadingOnDateExcludesZeroConfirmedRows() {
            assertCompletionFilter(() -> repository.findLatestCompletedFlowReadingOnDate(SCHEMA, 7L, DAY));
        }

        @Test
        void findLatestCompletedFlowReadingBeforeDateForSchemeExcludesZeroConfirmedRows() {
            assertCompletionFilter(() ->
                    repository.findLatestCompletedFlowReadingBeforeDateForScheme(SCHEMA, 7L, DAY));
        }
    }

    @Nested
    @DisplayName("completed flow reading finders")
    class CompletedFlowReadingFinders {

        private java.util.Map<String, Object> completedRow() {
            return row("id", 5L, "correlation_id", "corr-1", "created_by", 2L,
                    "reading_date", DAY, "confirmed_reading", new BigDecimal("500"));
        }

        @Test
        void findLatestCompletedFlowReadingForSchemeMapsTheProjection() {
            onQuery("flow_reading_table", completedRow());

            assertThat(repository.findLatestCompletedFlowReadingForScheme(SCHEMA, 7L))
                    .hasValueSatisfying(r -> {
                        assertThat(r.id()).isEqualTo(5L);
                        assertThat(r.readingDate()).isEqualTo(DAY);
                        assertThat(r.confirmedReading()).isEqualByComparingTo("500");
                    });
        }

        @Test
        void findLatestCompletedFlowReadingForSchemeGuardsAnInvalidSchemeId() {
            assertThat(repository.findLatestCompletedFlowReadingForScheme(SCHEMA, null)).isEmpty();
            assertThat(repository.findLatestCompletedFlowReadingForScheme(SCHEMA, 0L)).isEmpty();
        }

        @Test
        void findLatestCompletedFlowReadingOnDateMapsTheProjection() {
            onQuery("flow_reading_table", completedRow());

            assertThat(repository.findLatestCompletedFlowReadingOnDate(SCHEMA, 7L, DAY)).isPresent();
        }

        @Test
        void findLatestCompletedFlowReadingOnDateForUserGuardsItsArguments() {
            assertThat(repository.findLatestCompletedFlowReadingOnDateForUser(SCHEMA, null, 2L, DAY)).isEmpty();
            assertThat(repository.findLatestCompletedFlowReadingOnDateForUser(SCHEMA, 0L, 2L, DAY)).isEmpty();
            assertThat(repository.findLatestCompletedFlowReadingOnDateForUser(SCHEMA, 7L, null, DAY)).isEmpty();
            assertThat(repository.findLatestCompletedFlowReadingOnDateForUser(SCHEMA, 7L, 0L, DAY)).isEmpty();
            assertThat(repository.findLatestCompletedFlowReadingOnDateForUser(SCHEMA, 7L, 2L, null)).isEmpty();
        }

        @Test
        void findLatestCompletedFlowReadingOnDateForUserMapsTheProjection() {
            onQuery("flow_reading_table", completedRow());

            assertThat(repository.findLatestCompletedFlowReadingOnDateForUser(SCHEMA, 7L, 2L, DAY)).isPresent();
        }

        @Test
        void findLatestCompletedFlowReadingBeforeDateForSchemeGuardsItsArguments() {
            assertThat(repository.findLatestCompletedFlowReadingBeforeDateForScheme(SCHEMA, null, DAY)).isEmpty();
            assertThat(repository.findLatestCompletedFlowReadingBeforeDateForScheme(SCHEMA, 0L, DAY)).isEmpty();
            assertThat(repository.findLatestCompletedFlowReadingBeforeDateForScheme(SCHEMA, 7L, null)).isEmpty();
        }

        @Test
        void findLatestCompletedFlowReadingBeforeDateForSchemeMapsTheProjection() {
            onQuery("reading_date < ?", completedRow());

            assertThat(repository.findLatestCompletedFlowReadingBeforeDateForScheme(SCHEMA, 7L, DAY)).isPresent();
        }
    }
}
