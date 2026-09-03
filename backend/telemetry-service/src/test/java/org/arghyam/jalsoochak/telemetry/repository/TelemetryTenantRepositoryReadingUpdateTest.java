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
        void writesPayloadJsonAndFlowVisionIdOnAFullyMigratedSchema() {
            onColumnExists(true);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", "fv-1", "img", "reason", 2L);

            assertThat(capturedUpdateSql())
                    .contains("payload_json")
                    .contains("flowvision_correlation_id")
                    .contains("observation_time");
            assertThat(capturedUpdateArgs()).containsExactly(
                    READING_AT, DAY, new BigDecimal("10"), new BigDecimal("11"),
                    new BigDecimal("11"), new BigDecimal("10"), "corr-1", "fv-1",
                    "img", "reason", 2L, 5L);
        }

        @Test
        void omitsBothOptionalColumnsOnALegacySchema() {
            onColumnExists(false);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", null, "img", "reason", 2L);

            assertThat(capturedUpdateSql())
                    .doesNotContain("payload_json")
                    .doesNotContain("flowvision_correlation_id")
                    .contains("reading_at");
            assertThat(capturedUpdateArgs()).containsExactly(
                    READING_AT, DAY, new BigDecimal("10"), new BigDecimal("11"),
                    "corr-1", "img", "reason", 2L, 5L);
        }

        @Test
        void writesPayloadJsonOnlyWhenTheFlowVisionColumnIsAbsent() {
            onColumnsExisting("payload_json");

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", null, "img", "reason", 2L);

            assertThat(capturedUpdateSql()).contains("payload_json").doesNotContain("flowvision_correlation_id");
            assertThat(capturedUpdateArgs()).hasSize(11);
        }

        @Test
        void writesTheFlowVisionIdOnlyWhenPayloadJsonIsAbsent() {
            onColumnsExisting("flowvision_correlation_id");

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    new BigDecimal("10"), new BigDecimal("11"), "corr-1", "fv-1", "img", "reason", 2L);

            assertThat(capturedUpdateSql()).contains("flowvision_correlation_id").doesNotContain("payload_json");
            assertThat(capturedUpdateArgs()).hasSize(10);
        }

        @Test
        void onlyOverwritesAPlaceholderCorrelationId() {
            onColumnExists(false);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "corr-1", null, "img", null, 2L);

            // A real correlation id already on the row must survive; only the scheme-selection
            // placeholder (or an empty value) is replaced.
            assertThat(capturedUpdateSql())
                    .contains("correlation_id LIKE 'scheme-selection-%'")
                    .contains("ELSE correlation_id");
        }

        @Test
        void substitutesEmptyStringForANullImageUrl() {
            onColumnExists(false);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "corr-1", null, null, null, 2L);

            assertThat(capturedUpdateArgs()).contains("");
        }

        @Test
        void nineArgumentOverloadPassesNoFlowVisionId() {
            onColumnExists(true);

            repository.updateFlowReadingFromIngestion(SCHEMA, 5L, READING_AT,
                    BigDecimal.ONE, BigDecimal.ONE, "corr-1", "img", "reason", 2L);

            assertThat(capturedUpdateArgs()).containsSequence("corr-1", null);
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
        void findPreviousFlowReadingForSchemeJoinsAgainstTheTargetRow() {
            onQuery("JOIN tenant_as.flow_reading_table target", completedRow());

            assertThat(repository.findPreviousFlowReadingForScheme(SCHEMA, 100L)).isPresent();
        }

        @Test
        void findPreviousFlowReadingForSchemeGuardsAnInvalidReadingId() {
            assertThat(repository.findPreviousFlowReadingForScheme(SCHEMA, null)).isEmpty();
            assertThat(repository.findPreviousFlowReadingForScheme(SCHEMA, 0L)).isEmpty();
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

        @Test
        void findEarliestCompletedFlowReadingAfterDateForSchemeGuardsItsArguments() {
            assertThat(repository.findEarliestCompletedFlowReadingAfterDateForScheme(SCHEMA, null, DAY)).isEmpty();
            assertThat(repository.findEarliestCompletedFlowReadingAfterDateForScheme(SCHEMA, 0L, DAY)).isEmpty();
            assertThat(repository.findEarliestCompletedFlowReadingAfterDateForScheme(SCHEMA, 7L, null)).isEmpty();
        }

        @Test
        void findEarliestCompletedFlowReadingAfterDateForSchemeMapsTheProjection() {
            onQuery("flow_reading_table", completedRow());

            assertThat(repository.findEarliestCompletedFlowReadingAfterDateForScheme(SCHEMA, 7L, DAY)).isPresent();
        }

        @Test
        void findEarliestCompletedFlowReadingAfterDateMapsTheProjection() {
            onQuery("flow_reading_table", completedRow());

            assertThat(repository.findEarliestCompletedFlowReadingAfterDate(SCHEMA, 7L, 2L, DAY)).isPresent();
        }
    }
}
