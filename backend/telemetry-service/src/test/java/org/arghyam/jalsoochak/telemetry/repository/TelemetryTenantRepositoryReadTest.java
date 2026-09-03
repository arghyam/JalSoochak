package org.arghyam.jalsoochak.telemetry.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Read-path behaviour of {@link TelemetryTenantRepository}: schema validation, the per-tenant column
 * probes that select modern vs legacy SQL, the cross-tenant operator lookup, and the row mappers that
 * build each projection record.
 */
@DisplayName("TelemetryTenantRepository — read paths")
class TelemetryTenantRepositoryReadTest extends AbstractTelemetryTenantRepositoryTest {

    @Nested
    @DisplayName("schema name validation")
    class SchemaValidation {

        @Test
        void rejectsNullSchemaName() {
            assertThatThrownBy(() -> repository.existsSchemeById(null, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid schema name");
        }

        @Test
        void rejectsSchemaNameWithSqlInjectionPayload() {
            assertThatThrownBy(() -> repository.existsSchemeById("tenant_as; DROP TABLE users", 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid schema name");
        }

        @Test
        void rejectsUppercaseSchemaName() {
            assertThatThrownBy(() -> repository.findFirstSchemeForUser("Tenant_AS", 1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void acceptsLowercaseUnderscoreSchemaName() {
            onScalar("scheme_master_table", Boolean.class, Boolean.TRUE);

            assertThat(repository.existsSchemeById("tenant_up", 7L)).isTrue();
        }
    }

    @Nested
    @DisplayName("scheme lookups")
    class SchemeLookups {

        @Test
        void existsSchemeByIdReturnsFalseWhenProbeYieldsNull() {
            onScalar("scheme_master_table", Boolean.class, null);

            assertThat(repository.existsSchemeById(SCHEMA, 5L)).isFalse();
        }

        @Test
        void findSchemeIdByStateSchemeIdReturnsEmptyForBlankInput() {
            assertThat(repository.findSchemeIdByStateSchemeId(SCHEMA, "   ")).isEmpty();
            assertThat(repository.findSchemeIdByStateSchemeId(SCHEMA, null)).isEmpty();
        }

        @Test
        void findSchemeIdByStateSchemeIdMapsFirstRow() {
            onQuery("state_scheme_id = ?", row("id", 42L));

            assertThat(repository.findSchemeIdByStateSchemeId(SCHEMA, " SCH-1 ")).contains(42L);
        }

        @Test
        void findSchemeIdByStateSchemeIdExcludesPlaceholdersWhenColumnExists() {
            onColumnsExisting("is_auto_provisioned");
            onQuery("state_scheme_id = ?", row("id", 9L));

            repository.findSchemeIdByStateSchemeId(SCHEMA, "SCH-1");

            assertThat(allQuerySql())
                    .anySatisfy(sql -> assertThat(sql).contains("is_auto_provisioned = FALSE"));
        }

        @Test
        void findSchemeIdByStateSchemeIdOmitsPlaceholderFilterOnLegacySchema() {
            onColumnExists(false);
            onQuery("state_scheme_id = ?", row("id", 9L));

            repository.findSchemeIdByStateSchemeId(SCHEMA, "SCH-1");

            assertThat(allQuerySql())
                    .filteredOn(sql -> sql.contains("state_scheme_id = ?"))
                    .allSatisfy(sql -> assertThat(sql).doesNotContain("is_auto_provisioned"));
        }

        @Test
        void findSchemeIdByCentreSchemeIdReturnsEmptyForBlankInput() {
            assertThat(repository.findSchemeIdByCentreSchemeId(SCHEMA, "")).isEmpty();
        }

        @Test
        void findSchemeIdByCentreSchemeIdMapsFirstRow() {
            onQuery("centre_scheme_id = ?", row("id", 77L));

            assertThat(repository.findSchemeIdByCentreSchemeId(SCHEMA, "C-9")).contains(77L);
        }

        @Test
        void findSchemeChannelReadsChannelColumnWhenPresent() {
            onColumnsExisting("channel");
            onQuery("scheme_master_table", row("channel", 2));

            assertThat(repository.findSchemeChannel(SCHEMA, 3L)).contains(2);
        }

        @Test
        void findSchemeChannelFallsBackToNullLiteralWhenColumnMissing() {
            onColumnExists(false);

            assertThat(repository.findSchemeChannel(SCHEMA, 3L)).isEmpty();
            assertThat(allQuerySql())
                    .anySatisfy(sql -> assertThat(sql).contains("NULL::integer AS channel"));
        }

        /**
         * KNOWN GAP: the mapper can yield {@code null} for a nullable column, and
         * {@code Stream.findFirst()} rejects a null first element. A scheme row that exists but has
         * no channel therefore throws instead of returning {@link Optional#empty()}. Pinned here so
         * the behaviour is visible; the fix is {@code rows.stream().filter(Objects::nonNull)} (or
         * {@code Optional.ofNullable(rows.isEmpty() ? null : rows.get(0))}).
         */
        @Test
        void findSchemeChannelThrowsWhenStoredChannelIsNull() {
            onColumnsExisting("channel");
            onQuery("scheme_master_table", row("channel", null));

            assertThatThrownBy(() -> repository.findSchemeChannel(SCHEMA, 3L))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void schemeHasLatitudeAndLongitudeIsFalseForNullSchemeId() {
            assertThat(repository.schemeHasLatitudeAndLongitude(SCHEMA, null)).isFalse();
        }

        @Test
        void schemeHasLatitudeAndLongitudeRequiresBothCoordinates() {
            onColumnsExisting("latitude", "longitude");
            onQuery("scheme_master_table", row("latitude", 26.1, "longitude", null));

            assertThat(repository.schemeHasLatitudeAndLongitude(SCHEMA, 3L)).isFalse();
        }

        @Test
        void schemeHasLatitudeAndLongitudeIsTrueWhenBothPresent() {
            onColumnsExisting("latitude", "longitude");
            onQuery("scheme_master_table", row("latitude", 26.1, "longitude", 91.7));

            assertThat(repository.schemeHasLatitudeAndLongitude(SCHEMA, 3L)).isTrue();
        }

        @Test
        void schemeHasLatitudeAndLongitudeIsFalseWhenNoRowMatches() {
            onColumnsExisting("latitude", "longitude");

            assertThat(repository.schemeHasLatitudeAndLongitude(SCHEMA, 404L)).isFalse();
        }
    }

    @Nested
    @DisplayName("operator lookups")
    class OperatorLookups {

        @Test
        void findOperatorByIdMapsAllProjectionFields() {
            onColumnsExisting("language_id");
            onQuery("user_table", row(
                    "id", 11L,
                    "tenant_id", 3,
                    "title", "Asha",
                    "email", "asha@example.org",
                    "phone_number", "919999900001",
                    "language_id", 5));

            Optional<TelemetryOperator> operator = repository.findOperatorById(SCHEMA, 11L);

            assertThat(operator).isPresent();
            assertThat(operator.get().id()).isEqualTo(11L);
            assertThat(operator.get().tenantId()).isEqualTo(3);
            assertThat(operator.get().title()).isEqualTo("Asha");
            assertThat(operator.get().email()).isEqualTo("asha@example.org");
            assertThat(operator.get().languageId()).isEqualTo(5);
        }

        @Test
        void findOperatorByIdDecryptsPiiColumns() {
            onColumnsExisting("language_id");
            onQuery("user_table", row("id", 11L, "tenant_id", 3, "title", "enc:Asha",
                    "email", "enc:a@b.c", "phone_number", "enc:919999900001", "language_id", 1));
            org.mockito.Mockito.when(piiEncryptionService.safeDecrypt(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(inv -> inv.getArgument(0, String.class).replace("enc:", ""));

            Optional<TelemetryOperator> operator = repository.findOperatorById(SCHEMA, 11L);

            assertThat(operator).isPresent();
            assertThat(operator.get().title()).isEqualTo("Asha");
            assertThat(operator.get().email()).isEqualTo("a@b.c");
        }

        @Test
        void findOperatorByIdReturnsEmptyWhenNoRow() {
            assertThat(repository.findOperatorById(SCHEMA, 404L)).isEmpty();
        }

        @Test
        void findOperatorByPhoneAcrossTenantsReturnsEmptyForNonNumericPhone() {
            assertThat(repository.findOperatorByPhoneAcrossTenants("no-digits-here")).isEmpty();
        }

        @Test
        void findOperatorByPhoneAcrossTenantsReturnsEmptyForNullPhone() {
            assertThat(repository.findOperatorByPhoneAcrossTenants(null)).isEmpty();
        }

        @Test
        void findOperatorByPhoneAcrossTenantsScansTenantSchemasAndReturnsFirstMatch() {
            onQuery("FROM pg_namespace", row("nspname", "tenant_as"), row("nspname", "tenant_up"));
            onQuery("WHERE phone_number = ?", row(
                    "id", 21L, "tenant_id", 3, "title", "Asha",
                    "email", "a@b.c", "phone_number", "919999900002", "language_id", 1));

            Optional<TelemetryOperatorWithSchema> match =
                    repository.findOperatorByPhoneAcrossTenants("919999900002");

            assertThat(match).isPresent();
            assertThat(match.get().schemaName()).isEqualTo("tenant_as");
            assertThat(match.get().operator().id()).isEqualTo(21L);
        }

        @Test
        void findOperatorByPhoneAcrossTenantsPrefersRequestedTenant() {
            onQuery("FROM common_schema.tenant_master_table", row("state_code", "UP"));
            onQuery("WHERE phone_number = ?", row(
                    "id", 31L, "tenant_id", 9, "title", "Ravi",
                    "email", "r@b.c", "phone_number", "919999900003", "language_id", 1));

            Optional<TelemetryOperatorWithSchema> match =
                    repository.findOperatorByPhoneAcrossTenants("919999900003", 9);

            assertThat(match).isPresent();
            assertThat(match.get().schemaName()).isEqualTo("tenant_up");
        }

        @Test
        void findOperatorByPhoneAcrossTenantsFallsBackToFirstMatchWhenPreferredTenantHasNoRow() {
            // Preferred tenant 42 resolves to no schema, so the scan decides — and because a preferred
            // tenant was requested, a non-matching tenant is only used as a last-resort fallback.
            onQuery("FROM pg_namespace", row("nspname", "tenant_as"));
            onQuery("WHERE phone_number = ?", row(
                    "id", 41L, "tenant_id", 3, "title", "Meena",
                    "email", "m@b.c", "phone_number", "919999900004", "language_id", 1));

            Optional<TelemetryOperatorWithSchema> match =
                    repository.findOperatorByPhoneAcrossTenants("919999900004", 42);

            assertThat(match).isPresent();
            assertThat(match.get().operator().tenantId()).isEqualTo(3);
        }

        @Test
        void findOperatorByPhoneMatchesOnLast10DigitsDuringFullScan() {
            // No exact match on the indexed predicate; the fallback scan normalises both sides.
            onQuery("FROM pg_namespace", row("nspname", "tenant_as"));
            onQueryMatching(
                    List.of("FROM tenant_as.user_table"),
                    List.of(row("id", 51L, "tenant_id", 3, "title", "Sita",
                            "email", "s@b.c", "phone_number", "+91 99999-00005", "language_id", 1)));

            Optional<TelemetryOperatorWithSchema> match =
                    repository.findOperatorByPhoneAcrossTenants("9999900005");

            assertThat(match).isPresent();
            assertThat(match.get().operator().id()).isEqualTo(51L);
        }

        @Test
        void findOperatorByPhoneHashAcrossTenantsRejectsMalformedHash() {
            assertThat(repository.findOperatorByPhoneHashAcrossTenants("not-a-hash", null)).isEmpty();
            assertThat(repository.findOperatorByPhoneHashAcrossTenants("", null)).isEmpty();
            assertThat(repository.findOperatorByPhoneHashAcrossTenants(null, null)).isEmpty();
        }

        @Test
        void findOperatorByPhoneHashAcrossTenantsAcceptsWellFormedHash() {
            String hash = "a".repeat(64);
            onColumnsExisting("phone_number_hash");
            onQuery("FROM pg_namespace", row("nspname", "tenant_as"));
            onQuery("phone_number_hash = ?", row(
                    "id", 61L, "tenant_id", 3, "title", "Kiran",
                    "email", "k@b.c", "phone_number", "919999900006", "language_id", 1));

            Optional<TelemetryOperatorWithSchema> match =
                    repository.findOperatorByPhoneHashAcrossTenants(hash, null);

            assertThat(match).isPresent();
            assertThat(match.get().operator().id()).isEqualTo(61L);
        }

        @Test
        void findOperatorByPhoneHashAcrossTenantsReturnsEmptyWhenSchemaLacksHashColumn() {
            onColumnExists(false);
            onQuery("FROM pg_namespace", row("nspname", "tenant_as"));

            assertThat(repository.findOperatorByPhoneHashAcrossTenants("b".repeat(64), null)).isEmpty();
        }

        @Test
        void findUserIdByPhoneReturnsMappedOperatorId() {
            onQuery("WHERE phone_number = ?", row(
                    "id", 71L, "tenant_id", 3, "title", "Om",
                    "email", "o@b.c", "phone_number", "919999900007", "language_id", 1));

            assertThat(repository.findUserIdByPhone(SCHEMA, "919999900007")).contains(71L);
        }

        @Test
        void findUserIdByPhoneReturnsEmptyForBlankPhone() {
            assertThat(repository.findUserIdByPhone(SCHEMA, "")).isEmpty();
        }

        @Test
        void findUserIdByEmailIsNullForBlankEmail() {
            assertThat(repository.findUserIdByEmail(SCHEMA, "  ")).isNull();
            assertThat(repository.findUserIdByEmail(SCHEMA, null)).isNull();
        }

        @Test
        void findUserIdByEmailReturnsMatchingId() {
            onQuery("lower(email) = lower(?)", row("id", 81L));

            assertThat(repository.findUserIdByEmail(SCHEMA, " Asha@Example.org ")).isEqualTo(81L);
        }

        @Test
        void isOperatorMappedToSchemeReflectsExistsProbe() {
            onScalar("user_scheme_mapping_table", Boolean.class, Boolean.TRUE);

            assertThat(repository.isOperatorMappedToScheme(SCHEMA, 1L, 2L)).isTrue();
        }
    }

    @Nested
    @DisplayName("scheme membership")
    class SchemeMembership {

        @Test
        void findFirstSchemeForUserMapsSchemeId() {
            onQuery("user_scheme_mapping_table", row("scheme_id", 5L));

            assertThat(repository.findFirstSchemeForUser(SCHEMA, 1L)).contains(5L);
        }

        @Test
        void findFirstSchemeForUserIsEmptyWhenUnmapped() {
            assertThat(repository.findFirstSchemeForUser(SCHEMA, 1L)).isEmpty();
        }

        @Test
        void findSchemesForUserMapsEveryOption() {
            onQuery("user_scheme_mapping_table",
                    row("id", 5L, "name", "Scheme A"),
                    row("id", 6L, "name", "Scheme B"));

            List<TelemetrySchemeOption> options = repository.findSchemesForUser(SCHEMA, 1L);

            assertThat(options).hasSize(2);
            assertThat(options.get(0).id()).isEqualTo(5L);
            assertThat(options.get(0).name()).isEqualTo("Scheme A");
            assertThat(options.get(1).name()).isEqualTo("Scheme B");
        }

        @Test
        void isUserMappedToSchemeReflectsExistsProbe() {
            onScalar("user_scheme_mapping_table", Boolean.class, Boolean.FALSE);

            assertThat(repository.isUserMappedToScheme(SCHEMA, 1L, 2L)).isFalse();
        }

        @Test
        void findSectionOfficerUserIdsFiltersByUserType() {
            onQuery("user_type_master_table", row("user_id", 101L), row("user_id", 102L));

            assertThat(repository.findSectionOfficerUserIdsForScheme(SCHEMA, 9L))
                    .containsExactly(101L, 102L);
        }

        @Test
        void findSectionOfficerUserIdForSchemeReturnsFirst() {
            onQuery("user_type_master_table", row("user_id", 101L), row("user_id", 102L));

            assertThat(repository.findSectionOfficerUserIdForScheme(SCHEMA, 9L)).contains(101L);
        }

        @Test
        void findSubDivisionalOfficerUserIdsReturnsEmptyForNullScheme() {
            assertThat(repository.findSubDivisionalOfficerUserIdsForScheme(SCHEMA, null)).isEmpty();
            assertThat(repository.findSectionOfficerUserIdsForScheme(SCHEMA, null)).isEmpty();
        }

        @Test
        void findSubDivisionalOfficerUserIdsMapsRows() {
            onQuery("user_type_master_table", row("user_id", 201L));

            assertThat(repository.findSubDivisionalOfficerUserIdsForScheme(SCHEMA, 9L))
                    .containsExactly(201L);
        }
    }

    @Nested
    @DisplayName("language preference")
    class LanguagePreference {

        @Test
        void findUserLanguageIdIsEmptyWhenColumnAbsent() {
            onColumnExists(false);

            assertThat(repository.findUserLanguageId(SCHEMA, 1L)).isEmpty();
        }

        @Test
        void findUserLanguageIdReadsValueWhenColumnPresent() {
            onColumnsExisting("language_id");
            onQuery("SELECT language_id", row("language_id", 4));

            assertThat(repository.findUserLanguageId(SCHEMA, 1L)).contains(4);
        }

        /** KNOWN GAP: same nullable-column/{@code findFirst()} interaction as {@code findSchemeChannel}. */
        @Test
        void findUserLanguageIdThrowsWhenStoredLanguageIsNull() {
            onColumnsExisting("language_id");
            onQuery("SELECT language_id", row("language_id", null));

            assertThatThrownBy(() -> repository.findUserLanguageId(SCHEMA, 1L))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void updateUserLanguageIdFailsFastWhenColumnAbsent() {
            onColumnExists(false);

            assertThatThrownBy(() -> repository.updateUserLanguageId(SCHEMA, 1L, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("language_id");
        }
    }

    @Nested
    @DisplayName("reading lookups")
    class ReadingLookups {

        @Test
        void findLatestConfirmedReadingSnapshotMapsReadingAndTimestamp() {
            onQuery("confirmed_reading > 0", row(
                    "confirmed_reading", new BigDecimal("1234.50"),
                    "created_at", LocalDateTime.of(2026, 3, 1, 7, 30)));

            Optional<TelemetryConfirmedReadingSnapshot> snapshot =
                    repository.findLatestConfirmedReadingSnapshot(SCHEMA, 5L, null);

            assertThat(snapshot).isPresent();
            assertThat(snapshot.get().confirmedReading()).isEqualByComparingTo("1234.50");
            assertThat(snapshot.get().createdAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 7, 30));
        }

        @Test
        void findLatestConfirmedReadingSnapshotToleratesNullTimestamp() {
            onQuery("confirmed_reading > 0",
                    row("confirmed_reading", new BigDecimal("10"), "created_at", null));

            Optional<TelemetryConfirmedReadingSnapshot> snapshot =
                    repository.findLatestConfirmedReadingSnapshot(SCHEMA, 5L, null);

            assertThat(snapshot).isPresent();
            assertThat(snapshot.get().createdAt()).isNull();
        }

        @Test
        void findLatestConfirmedReadingSnapshotAddsExclusionPredicate() {
            onQuery("confirmed_reading > 0",
                    row("confirmed_reading", new BigDecimal("10"), "created_at", null));

            repository.findLatestConfirmedReadingSnapshot(SCHEMA, 5L, 99L);

            assertThat(allQuerySql()).anySatisfy(sql -> assertThat(sql).contains("id <> ?"));
        }

        @Test
        void findLastConfirmedReadingUnwrapsSnapshot() {
            onQuery("confirmed_reading > 0", row(
                    "confirmed_reading", new BigDecimal("88.00"), "created_at", null));

            assertThat(repository.findLastConfirmedReading(SCHEMA, 5L, null))
                    .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("88.00"));
        }

        @Test
        void findLatestConfirmedReadingSnapshotBeforeDateRequiresCutoff() {
            assertThatThrownBy(() ->
                    repository.findLatestConfirmedReadingSnapshotBeforeDate(SCHEMA, 5L, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cutoffDateExclusive");
        }

        @Test
        void findLatestConfirmedReadingSnapshotBeforeDateMapsRow() {
            onQuery("confirmed_reading > 0", row(
                    "confirmed_reading", new BigDecimal("55"),
                    "created_at", LocalDateTime.of(2026, 2, 1, 6, 0)));

            assertThat(repository.findLatestConfirmedReadingSnapshotBeforeDate(
                    SCHEMA, 5L, LocalDate.of(2026, 2, 2), 7L)).isPresent();
        }

        @Test
        void findLatestConfirmedReadingSnapshotForDateRequiresDate() {
            assertThatThrownBy(() ->
                    repository.findLatestConfirmedReadingSnapshotForDate(SCHEMA, 5L, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("readingDate");
        }

        @Test
        void findLatestConfirmedReadingSnapshotForDateMapsRow() {
            onQuery("confirmed_reading > 0", row(
                    "confirmed_reading", new BigDecimal("66"), "created_at", null));

            assertThat(repository.findLatestConfirmedReadingSnapshotForDate(
                    SCHEMA, 5L, LocalDate.of(2026, 2, 2), 7L)).isPresent();
        }

        @Test
        void findRecentDailyConfirmedReadingsGuardsInvalidArguments() {
            assertThat(repository.findRecentDailyConfirmedReadings(SCHEMA, null, null, 14)).isEmpty();
            assertThat(repository.findRecentDailyConfirmedReadings(SCHEMA, 5L, null, 0)).isEmpty();
            assertThat(repository.findRecentDailyConfirmedReadings(SCHEMA, 5L, null, -1)).isEmpty();
        }

        @Test
        void findRecentDailyConfirmedReadingsMapsOneRowPerDay() {
            onQuery("DISTINCT ON (reading_date)",
                    row("reading_date", LocalDate.of(2026, 3, 2), "confirmed_reading", new BigDecimal("120")),
                    row("reading_date", LocalDate.of(2026, 3, 1), "confirmed_reading", new BigDecimal("100")));

            List<DailyConfirmedReading> readings =
                    repository.findRecentDailyConfirmedReadings(SCHEMA, 5L, 9L, 16);

            assertThat(readings).hasSize(2);
            assertThat(readings.get(0).day()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(readings.get(0).confirmedReading()).isEqualByComparingTo("120");
            assertThat(readings.get(1).day()).isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        void supportsConfirmedReadingSourceTracksColumnPresence() {
            onColumnsExisting("confirmed_reading_source");
            assertThat(repository.supportsConfirmedReadingSource(SCHEMA)).isTrue();
        }

        @Test
        void supportsConfirmedReadingSourceIsFalseOnPreMigrationSchema() {
            onColumnExists(false);
            assertThat(repository.supportsConfirmedReadingSource(SCHEMA)).isFalse();
        }

        @Test
        void findReadingByCorrelationIdAlsoMatchesFlowVisionIdWhenColumnExists() {
            onColumnsExisting("flowvision_correlation_id");
            onQuery("flow_reading_table", row("id", 5L, "correlation_id", "corr-1", "created_by", 2L));

            Optional<TelemetryReadingRecord> record =
                    repository.findReadingByCorrelationId(SCHEMA, "corr-1");

            assertThat(record).isPresent();
            assertThat(record.get().correlationId()).isEqualTo("corr-1");
            assertThat(allQuerySql())
                    .anySatisfy(sql -> assertThat(sql).contains("flowvision_correlation_id = ?"));
        }

        @Test
        void findReadingByCorrelationIdUsesSinglePredicateOnLegacySchema() {
            onColumnExists(false);
            onQuery("flow_reading_table", row("id", 5L, "correlation_id", "corr-1", "created_by", 2L));

            repository.findReadingByCorrelationId(SCHEMA, "corr-1");

            assertThat(allQuerySql())
                    .filteredOn(sql -> sql.contains("SELECT id, correlation_id, created_by"))
                    .allSatisfy(sql -> assertThat(sql).doesNotContain("flowvision_correlation_id"));
        }

        @Test
        void findFlowReadingDetailsByCorrelationIdMapsEveryColumn() {
            onQuery("AS reading_time", row(
                    "id", 5L,
                    "scheme_id", 7L,
                    "created_by", 2L,
                    "correlation_id", "corr-1",
                    "extracted_reading", new BigDecimal("10.5"),
                    "confirmed_reading", new BigDecimal("11.5"),
                    "image_url", "https://minio/img.jpg",
                    "reading_date", LocalDate.of(2026, 3, 1),
                    "reading_time", LocalDateTime.of(2026, 3, 1, 6, 15),
                    "channel", "BFM"));

            Optional<TelemetryLatestFlowReadingRecord> record =
                    repository.findFlowReadingDetailsByCorrelationId(SCHEMA, "corr-1");

            assertThat(record).isPresent();
            TelemetryLatestFlowReadingRecord value = record.get();
            assertThat(value.id()).isEqualTo(5L);
            assertThat(value.schemeId()).isEqualTo(7L);
            assertThat(value.createdBy()).isEqualTo(2L);
            assertThat(value.extractedReading()).isEqualByComparingTo("10.5");
            assertThat(value.confirmedReading()).isEqualByComparingTo("11.5");
            assertThat(value.imageUrl()).isEqualTo("https://minio/img.jpg");
            assertThat(value.readingDate()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(value.readingAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 6, 15));
            assertThat(value.channel()).isEqualTo("BFM");
        }

        @Test
        void findLatestFlowReadingForDateMapsProjection() {
            onQuery("flow_reading_table", row(
                    "id", 5L, "correlation_id", "c", "created_by", 2L,
                    "extracted_reading", new BigDecimal("1"), "confirmed_reading", new BigDecimal("2")));

            Optional<TelemetryFlowReadingDetails> details = repository.findLatestFlowReadingForDate(
                    SCHEMA, 7L, 2L, LocalDate.of(2026, 3, 1));

            assertThat(details).isPresent();
            assertThat(details.get().confirmedReading()).isEqualByComparingTo("2");
        }

        @Test
        void findLatestCompletedReadingForTodayMapsProjection() {
            onQuery("flow_reading_table", row("id", 5L, "correlation_id", "c", "created_by", 2L));

            assertThat(repository.findLatestCompletedReadingForToday(SCHEMA, 7L, 2L)).isPresent();
        }

        @Test
        void findLatestCompletedReadingForPreviousDayUsesIstIntervalPredicate() {
            onQuery("flow_reading_table", row("id", 5L, "correlation_id", "c", "created_by", 2L));

            repository.findLatestCompletedReadingForPreviousDay(SCHEMA, 7L, 2L);

            assertThat(allQuerySql()).anySatisfy(sql ->
                    assertThat(sql).contains("INTERVAL '1 day'").contains("Asia/Kolkata"));
        }

        @Test
        void findLatestFlowReadingByOperatorMapsProjection() {
            onQuery("AS reading_time", row(
                    "id", 5L, "scheme_id", 7L, "created_by", 2L, "correlation_id", "c",
                    "extracted_reading", new BigDecimal("1"), "confirmed_reading", new BigDecimal("2"),
                    "image_url", "", "reading_date", LocalDate.of(2026, 3, 1),
                    "reading_time", LocalDateTime.of(2026, 3, 1, 6, 0), "channel", "ELM"));

            assertThat(repository.findLatestFlowReadingByOperator(SCHEMA, 2L))
                    .hasValueSatisfying(r -> assertThat(r.channel()).isEqualTo("ELM"));
        }

        @Test
        void findLatestCompletedFlowReadingBeforeDateMapsProjection() {
            onQuery("reading_date < ?", row(
                    "id", 5L, "correlation_id", "c", "created_by", 2L,
                    "reading_date", LocalDate.of(2026, 2, 28), "confirmed_reading", new BigDecimal("9")));

            assertThat(repository.findLatestCompletedFlowReadingBeforeDate(
                    SCHEMA, 7L, 2L, LocalDate.of(2026, 3, 1)))
                    .hasValueSatisfying(r -> assertThat(r.readingDate())
                            .isEqualTo(LocalDate.of(2026, 2, 28)));
        }

        @Test
        void findLatestCompletedFlowReadingOnDateGuardsInvalidArguments() {
            assertThat(repository.findLatestCompletedFlowReadingOnDate(SCHEMA, null, LocalDate.now())).isEmpty();
            assertThat(repository.findLatestCompletedFlowReadingOnDate(SCHEMA, 0L, LocalDate.now())).isEmpty();
            assertThat(repository.findLatestCompletedFlowReadingOnDate(SCHEMA, 5L, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("anomaly lookups")
    class AnomalyLookups {

        @Test
        void countAnomaliesByTypeForTodayDefaultsToZero() {
            onScalar("anomaly_table", Integer.class, null);

            assertThat(repository.countAnomaliesByTypeForToday(SCHEMA, 1L, 2L, 3)).isZero();
        }

        @Test
        void countAnomaliesByTypeForTodayReturnsProbeValue() {
            onScalar("anomaly_table", Integer.class, 4);

            assertThat(repository.countAnomaliesByTypeForToday(SCHEMA, 1L, 2L, 3)).isEqualTo(4);
        }

        @Test
        void findAnomalyDatesByTypeMapsDates() {
            onQuery("anomaly_table",
                    row("reading_date", LocalDate.of(2026, 3, 2)),
                    row("reading_date", LocalDate.of(2026, 3, 1)));

            assertThat(repository.findAnomalyDatesByType(SCHEMA, 1L, 2L, 3, 7))
                    .containsExactly(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 1));
        }

        @Test
        void findAnomalyDatesByTypeClampsLimitToAtLeastOne() {
            onQuery("anomaly_table", row("reading_date", LocalDate.of(2026, 3, 2)));

            assertThat(repository.findAnomalyDatesByType(SCHEMA, 1L, 2L, 3, 0)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("tenant metadata")
    class TenantMetadata {

        @Test
        void findSchemaNameByTenantIdBuildsSchemaFromStateCode() {
            onQuery("tenant_master_table", row("state_code", " As "));

            assertThat(repository.findSchemaNameByTenantId(3)).contains("tenant_as");
        }

        @Test
        void findSchemaNameByTenantIdIgnoresBlankStateCode() {
            onQuery("tenant_master_table", row("state_code", "  "));

            assertThat(repository.findSchemaNameByTenantId(3)).isEmpty();
        }

        @Test
        void findTenantIdBySchemaNameReadsCommonSchema() {
            onQuery("tenant_master_table", row("id", 17));

            assertThat(repository.findTenantIdBySchemaName("tenant_as")).isEqualTo(17);
        }

        @Test
        void findTenantIdBySchemaNameIsNullWhenUnknown() {
            assertThat(repository.findTenantIdBySchemaName("tenant_as")).isNull();
        }

        @Test
        void findTenantIdBySchemaNameRejectsNonTenantSchema() {
            assertThat(repository.findTenantIdBySchemaName("common_schema")).isNull();
        }

        @Test
        void hashSubmittedPhoneReturnsNullForBlankInput() {
            assertThat(repository.hashSubmittedPhone("   ")).isNull();
            assertThat(repository.hashSubmittedPhone(null)).isNull();
        }

        @Test
        void hashSubmittedPhoneHashesDigitsOnly() {
            assertThat(repository.hashSubmittedPhone("+91 99999-00008"))
                    .isEqualTo("hmac(919999900008)");
        }
    }

    @Nested
    @DisplayName("metadata caching")
    class MetadataCaching {

        @Test
        void invalidateMetadataCachesForcesTenantSchemaRequery() {
            onQuery("FROM pg_namespace", row("nspname", "tenant_as"));

            repository.findOperatorByPhoneAcrossTenants("919999900009");
            repository.invalidateMetadataCaches();
            repository.findOperatorByPhoneAcrossTenants("919999900010");

            org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(2))
                    .query(org.mockito.ArgumentMatchers.contains("FROM pg_namespace"),
                            org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class));
        }

        @Test
        void tenantSchemaListIsQueriedOncePerTtlWindow() {
            onQuery("FROM pg_namespace", row("nspname", "tenant_as"));

            repository.findOperatorByPhoneAcrossTenants("919999900011");
            repository.findOperatorByPhoneAcrossTenants("919999900012");

            org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(1))
                    .query(org.mockito.ArgumentMatchers.contains("FROM pg_namespace"),
                            org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class));
        }

        @Test
        void disablingMetadataCacheRequeriesSchemasEveryCall() {
            org.springframework.test.util.ReflectionTestUtils
                    .setField(repository, "metadataCacheEnabled", false);
            onQuery("FROM pg_namespace", row("nspname", "tenant_as"));

            repository.findOperatorByPhoneAcrossTenants("919999900013");
            repository.findOperatorByPhoneAcrossTenants("919999900014");

            org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(2))
                    .query(org.mockito.ArgumentMatchers.contains("FROM pg_namespace"),
                            org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class));
        }
    }
}
