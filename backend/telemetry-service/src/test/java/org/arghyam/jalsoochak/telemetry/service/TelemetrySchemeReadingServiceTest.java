package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.config.TenantContext;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryCompletedFlowReading;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.capture.ManualReadingMaxValues;
import org.arghyam.jalsoochak.telemetry.service.capture.SubmittedValueCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Back-office correction of a scheme's already-submitted final reading.
 *
 * <p>The endpoint has no JWT: the caller is authenticated by the per-tenant {@code X-Api-Key} at the
 * controller, and authorisation within that tenant is phone-number → user → scheme-mapping, so the
 * reject paths matter as much as the happy path. A correction publishes the corrected reading again,
 * and analytics recalculates the corrected day and the day after it, since both deltas move.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TelemetrySchemeReadingService")
class TelemetrySchemeReadingServiceTest {

    private static final String SCHEMA = "tenant_as";
    private static final String PHONE = "919999900001";
    private static final Long SCHEME_ID = 7L;
    private static final LocalDate TARGET_DAY = LocalDate.of(2026, 3, 1);

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private ReadingRepublisher readingRepublisher;
    @Mock
    private ManualReadingMaxValues manualReadingMaxValues;

    private TelemetrySchemeReadingService service;

    private static TelemetryCompletedFlowReading reading(Long id, LocalDate day, String confirmed, Long createdBy) {
        return new TelemetryCompletedFlowReading(id, "corr-" + id, createdBy, day,
                confirmed == null ? null : new BigDecimal(confirmed));
    }

    @BeforeEach
    void setUp() {
        service = new TelemetrySchemeReadingService(telemetryTenantRepository, readingRepublisher,
                new SubmittedValueCapture(manualReadingMaxValues));
        TenantContext.setSchema(SCHEMA);
        when(telemetryTenantRepository.findUserIdByPhone(SCHEMA, PHONE)).thenReturn(Optional.of(11L));
        when(telemetryTenantRepository.findTenantIdBySchemaName(SCHEMA)).thenReturn(17);
        when(telemetryTenantRepository.isUserMappedToScheme(SCHEMA, 11L, SCHEME_ID)).thenReturn(true);
        when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                .thenReturn(Optional.of(reading(100L, TARGET_DAY, "500", 22L)));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("tenant resolution")
    class TenantResolution {

        private static final String OTHER_SCHEMA = "tenant_mp";

        @Test
        @DisplayName("the API key's tenant wins over the X-Tenant-Code header")
        void apiKeyTenantWinsOverHeader() {
            // The caller sets X-Tenant-Code to a tenant that is not theirs; the authenticated key
            // resolves to tenant 42 / tenant_as. The write must land in the key's schema.
            TenantContext.setSchema(OTHER_SCHEMA);
            when(telemetryTenantRepository.findSchemaNameByTenantId(42)).thenReturn(Optional.of(SCHEMA));

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null, 42);

            verify(telemetryTenantRepository).findUserIdByPhone(SCHEMA, PHONE);
            verify(telemetryTenantRepository, never()).findUserIdByPhone(OTHER_SCHEMA, PHONE);
        }

        @Test
        @DisplayName("rejects a tenant id that resolves to no schema")
        void rejectsUnknownTenantId() {
            when(telemetryTenantRepository.findSchemaNameByTenantId(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, new BigDecimal("600"), null, 99))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Tenant not found");
        }

        @Test
        @DisplayName("falls back to the header only when no tenant id is supplied")
        void fallsBackToHeaderWhenNoTenantId() {
            TenantContext.setSchema(SCHEMA);

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null, null);

            verify(telemetryTenantRepository).findUserIdByPhone(SCHEMA, PHONE);
            verify(telemetryTenantRepository, never()).findSchemaNameByTenantId(any());
        }
    }

    @Nested
    @DisplayName("request validation")
    class Validation {

        @Test
        void rejectsANonPositiveSchemeId() {
            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    null, PHONE, BigDecimal.TEN, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("schemeId must be a positive integer");

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    0L, PHONE, BigDecimal.TEN, null))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void rejectsAMissingPhoneNumber() {
            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, "  ", BigDecimal.TEN, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("phoneNumber is required");
        }

        @Test
        void rejectsANonPositiveReading() {
            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, null, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("reading must be greater than zero");

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, BigDecimal.ZERO, null))
                    .isInstanceOf(ResponseStatusException.class);

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, new BigDecimal("-1"), null))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void rejectsARequestWithNoResolvedTenantSchema() {
            TenantContext.clear();

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, BigDecimal.TEN, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("X-Tenant-Code");
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        void rejectsAPhoneNumberWithNoMatchingUser() {
            when(telemetryTenantRepository.findUserIdByPhone(SCHEMA, PHONE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, BigDecimal.TEN, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.UNAUTHORIZED));
        }

        @Test
        void rejectsASchemaWithNoTenantRow() {
            when(telemetryTenantRepository.findTenantIdBySchemaName(SCHEMA)).thenReturn(null);

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, BigDecimal.TEN, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        void rejectsAUserNotMappedToTheScheme() {
            when(telemetryTenantRepository.isUserMappedToScheme(SCHEMA, 11L, SCHEME_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, BigDecimal.TEN, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));

            verify(telemetryTenantRepository, never())
                    .updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any());
            verify(readingRepublisher, never()).republish(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("target reading selection")
    class TargetSelection {

        @Test
        void usesTheLatestSubmittedReadingWhenNoDateIsGiven() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            verify(telemetryTenantRepository).findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID);
            verify(telemetryTenantRepository, never())
                    .findLatestCompletedFlowReadingOnDate(anyString(), anyLong(), any());
        }

        @Test
        void usesTheReadingForTheGivenDateWhenOneIsSupplied() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingOnDate(SCHEMA, SCHEME_ID, TARGET_DAY))
                    .thenReturn(Optional.of(reading(100L, TARGET_DAY, "500", 22L)));

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), TARGET_DAY);

            verify(telemetryTenantRepository).findLatestCompletedFlowReadingOnDate(SCHEMA, SCHEME_ID, TARGET_DAY);
        }

        @Test
        void reportsNotFoundWhenTheSchemeHasNoSubmittedReading() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, BigDecimal.TEN, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No submitted reading found for this scheme");
        }

        @Test
        void reportsNotFoundWithADateSpecificReasonWhenADateWasGiven() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingOnDate(SCHEMA, SCHEME_ID, TARGET_DAY))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, BigDecimal.TEN, TARGET_DAY))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No submitted reading found for the provided date");
        }

        @Test
        void reportsAnInternalErrorWhenTheTargetReadingHasNoDate() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                    .thenReturn(Optional.of(reading(100L, null, "500", 22L)));

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, BigDecimal.TEN, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    @Nested
    @DisplayName("correction and republish")
    class Correction {

        @Test
        void writesTheCorrectedReadingAgainstTheRequestingUser() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            // An officer's correction is a manual override of the number, so the row must stop
            // claiming its confirmed_reading is the value the AI extracted.
            // The target is always a BFM row and the request has no unit, so the value is in m3.
            verify(telemetryTenantRepository)
                    .updateConfirmedReading(SCHEMA, 100L, new BigDecimal("600"), 11L,
                            RolloverResolutionService.SOURCE_MANUAL, "m3");
        }

        @Test
        void leavesTheExistingProvenanceWhenTheCorrectionRestatesTheStoredValue() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("500"), null);

            // Re-submitting the value already on the row changes nothing, so a ROLLOVER_RESOLVED or
            // EXTERNALLY_ASSERTED marker must survive it.
            verify(telemetryTenantRepository)
                    .updateConfirmedReading(SCHEMA, 100L, new BigDecimal("500"), 11L, null, "m3");
        }

        @Test
        void neverTouchesExtractedReadingWhenCorrecting() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            verify(telemetryTenantRepository, never())
                    .updateReadingValues(anyString(), anyLong(), any(), anyLong());
        }

        @Test
        void returnsASuccessResponseDescribingTheCorrection() {
            var response = service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, new BigDecimal("600"), null);

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getSchemeId()).isEqualTo(SCHEME_ID);
            assertThat(response.getReadingDate()).isEqualTo(TARGET_DAY.toString());
            assertThat(response.getFinalReading()).isEqualByComparingTo("600");
            assertThat(response.getMessage()).isEqualTo("Final reading updated successfully.");
        }

        @Test
        void publishesTheCorrectedRowAgainAfterWritingIt() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            // The row is published from what is stored, so it goes out after the write, under the
            // tenant the correction was applied to.
            InOrder order = inOrder(telemetryTenantRepository, readingRepublisher);
            order.verify(telemetryTenantRepository)
                    .updateConfirmedReading(SCHEMA, 100L, new BigDecimal("600"), 11L,
                            RolloverResolutionService.SOURCE_MANUAL, "m3");
            order.verify(readingRepublisher).republish(SCHEMA, 17, 100L);
        }

        @Test
        @DisplayName("a reading above the tenant's BFM maximum is refused and nothing is written")
        void rejectsAReadingAboveTheMaximum() {
            when(manualReadingMaxValues.maxFor(17, ReadingChannel.BFM)).thenReturn(Optional.of(new BigDecimal("550")));

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, new BigDecimal("600"), null))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getReason()).isEqualTo("Reading can't be more than 550 m³.");
                    });
            verify(telemetryTenantRepository, never())
                    .updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any());
            verify(readingRepublisher, never()).republish(any(), any(), any());
        }

        @Test
        @DisplayName("a reading at the tenant's BFM maximum is written")
        void acceptsAReadingAtTheMaximum() {
            when(manualReadingMaxValues.maxFor(17, ReadingChannel.BFM)).thenReturn(Optional.of(new BigDecimal("600")));

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            verify(telemetryTenantRepository).updateConfirmedReading(SCHEMA, 100L, new BigDecimal("600"), 11L,
                    RolloverResolutionService.SOURCE_MANUAL, "m3");
        }

        @Test
        void propagatesAnUnexpectedRepositoryFailure() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                    .thenThrow(new IllegalStateException("connection reset"));

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, PHONE, BigDecimal.TEN, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("connection reset");
        }
    }
}
