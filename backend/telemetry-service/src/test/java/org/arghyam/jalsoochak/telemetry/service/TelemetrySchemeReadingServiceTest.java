package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReportingChannel;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An SO/SDO's correction of a scheme's already-submitted final reading.
 *
 * <p>The tenant and the user come from the officer's verified token, and authorisation within that
 * tenant is user → scheme mapping, so the reject paths matter as much as the happy path. A correction
 * publishes the corrected reading again, and analytics recalculates the corrected day and the day
 * after it, since both deltas move.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TelemetrySchemeReadingService")
class TelemetrySchemeReadingServiceTest {

    private static final String SCHEMA = "tenant_as";
    private static final String USER_UUID = "5b0f8f7e-2a4c-4d8e-9a51-0c6f3c1d2e7a";
    private static final StaffCaller CALLER = new StaffCaller("AS", USER_UUID);
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
        when(telemetryTenantRepository.findTenantIdBySchemaName(SCHEMA)).thenReturn(17);
        when(telemetryTenantRepository.findUserIdByUuid(SCHEMA, USER_UUID)).thenReturn(Optional.of(11L));
        when(telemetryTenantRepository.isUserMappedToScheme(SCHEMA, 11L, SCHEME_ID)).thenReturn(true);
        when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                .thenReturn(Optional.of(reading(100L, TARGET_DAY, "500", 22L)));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static HttpStatus statusOf(Throwable e) {
        return HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value());
    }

    @Nested
    @DisplayName("caller resolution")
    class CallerResolution {

        @Test
        @DisplayName("writes into the token's tenant and ignores the X-Tenant-Code header")
        void writesIntoTheTokensTenant() {
            // TenantContext is what the unauthenticated X-Tenant-Code header sets. It must not select
            // the schema.
            TenantContext.setSchema("tenant_mp");

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, new BigDecimal("600"), null, CALLER);

            verify(telemetryTenantRepository).findUserIdByUuid(SCHEMA, USER_UUID);
            verify(telemetryTenantRepository, never()).findUserIdByUuid(eq("tenant_mp"), any());
        }

        @Test
        @DisplayName("matches the tenant claim case-insensitively")
        void matchesTheTenantClaimCaseInsensitively() {
            service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, new BigDecimal("600"), null, new StaffCaller(" as ", USER_UUID));

            verify(telemetryTenantRepository).findUserIdByUuid(SCHEMA, USER_UUID);
        }

        @Test
        @DisplayName("a request with no caller is unauthenticated")
        void rejectsARequestWithNoCaller() {
            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.TEN, null, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.UNAUTHORIZED));

            verifyNoInteractions(telemetryTenantRepository, readingRepublisher);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "as;drop", "../as", "tenant-as"})
        @DisplayName("a token whose tenant claim can name no schema is refused before any lookup")
        void rejectsAnUnusableTenantClaim(String tenantStateCode) {
            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.TEN, null, new StaffCaller(tenantStateCode, USER_UUID)))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.FORBIDDEN));

            verifyNoInteractions(telemetryTenantRepository, readingRepublisher);
        }

        @Test
        @DisplayName("a token naming no tenant is refused")
        void rejectsATenantClaimWithNoTenantRow() {
            when(telemetryTenantRepository.findTenantIdBySchemaName(SCHEMA)).thenReturn(null);

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.TEN, null, CALLER))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("a token whose subject is no live user of the tenant is refused")
        void rejectsASubjectWithNoMatchingUser() {
            when(telemetryTenantRepository.findUserIdByUuid(SCHEMA, USER_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.TEN, null, CALLER))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("an officer not mapped to the scheme is refused and nothing is written")
        void rejectsAUserNotMappedToTheScheme() {
            when(telemetryTenantRepository.isUserMappedToScheme(SCHEMA, 11L, SCHEME_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.TEN, null, CALLER))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.FORBIDDEN));

            verify(telemetryTenantRepository, never())
                    .updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any(), any());
            verify(readingRepublisher, never()).republish(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("request validation")
    class Validation {

        @Test
        void rejectsANonPositiveSchemeId() {
            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    null, BigDecimal.TEN, null, CALLER))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("schemeId must be a positive integer");

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    0L, BigDecimal.TEN, null, CALLER))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void rejectsANonPositiveReading() {
            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, null, null, CALLER))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("reading must be greater than zero");

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.ZERO, null, CALLER))
                    .isInstanceOf(ResponseStatusException.class);

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, new BigDecimal("-1"), null, CALLER))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("target reading selection")
    class TargetSelection {

        @Test
        void usesTheLatestSubmittedReadingWhenNoDateIsGiven() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, new BigDecimal("600"), null, CALLER);

            verify(telemetryTenantRepository).findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID);
            verify(telemetryTenantRepository, never())
                    .findLatestCompletedFlowReadingOnDate(anyString(), anyLong(), any());
        }

        @Test
        void usesTheReadingForTheGivenDateWhenOneIsSupplied() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingOnDate(SCHEMA, SCHEME_ID, TARGET_DAY))
                    .thenReturn(Optional.of(reading(100L, TARGET_DAY, "500", 22L)));

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, new BigDecimal("600"), TARGET_DAY, CALLER);

            verify(telemetryTenantRepository).findLatestCompletedFlowReadingOnDate(SCHEMA, SCHEME_ID, TARGET_DAY);
        }

        @Test
        void reportsNotFoundWhenTheSchemeHasNoSubmittedReading() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.TEN, null, CALLER))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No submitted reading found for this scheme");
        }

        @Test
        void reportsNotFoundWithADateSpecificReasonWhenADateWasGiven() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingOnDate(SCHEMA, SCHEME_ID, TARGET_DAY))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.TEN, TARGET_DAY, CALLER))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No submitted reading found for the provided date");
        }

        @Test
        void reportsAnInternalErrorWhenTheTargetReadingHasNoDate() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                    .thenReturn(Optional.of(reading(100L, null, "500", 22L)));

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.TEN, null, CALLER))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    @Nested
    @DisplayName("correction and republish")
    class Correction {

        @Test
        void writesTheCorrectedReadingAgainstTheRequestingOfficerOnTheWebChannel() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, new BigDecimal("600"), null, CALLER);

            // An officer's correction is a manual override of the number, so the row must stop
            // claiming its confirmed_reading is the value the AI extracted.
            // The target is always a BFM row and the request has no unit, so the value is in m3.
            verify(telemetryTenantRepository)
                    .updateConfirmedReading(SCHEMA, 100L, new BigDecimal("600"), 11L,
                            RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WEB);
        }

        @Test
        void leavesTheExistingProvenanceWhenTheCorrectionRestatesTheStoredValue() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, new BigDecimal("500"), null, CALLER);

            // Re-submitting the value already on the row changes nothing, so a ROLLOVER_RESOLVED or
            // EXTERNALLY_ASSERTED marker must survive it.
            verify(telemetryTenantRepository)
                    .updateConfirmedReading(SCHEMA, 100L, new BigDecimal("500"), 11L, null, "m3", ReportingChannel.WEB);
        }

        @Test
        void neverTouchesExtractedReadingWhenCorrecting() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, new BigDecimal("600"), null, CALLER);

            verify(telemetryTenantRepository, never())
                    .updateReadingValues(anyString(), anyLong(), any(), anyLong());
        }

        @Test
        void returnsASuccessResponseDescribingTheCorrection() {
            var response = service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, new BigDecimal("600"), null, CALLER);

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getSchemeId()).isEqualTo(SCHEME_ID);
            assertThat(response.getReadingDate()).isEqualTo(TARGET_DAY.toString());
            assertThat(response.getFinalReading()).isEqualByComparingTo("600");
            assertThat(response.getMessage()).isEqualTo("Final reading updated successfully.");
        }

        @Test
        void publishesTheCorrectedRowAgainAfterWritingIt() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, new BigDecimal("600"), null, CALLER);

            // The row is published from what is stored, so it goes out after the write, under the
            // tenant the correction was applied to.
            InOrder order = inOrder(telemetryTenantRepository, readingRepublisher);
            order.verify(telemetryTenantRepository)
                    .updateConfirmedReading(SCHEMA, 100L, new BigDecimal("600"), 11L,
                            RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WEB);
            order.verify(readingRepublisher).republish(SCHEMA, 17, 100L);
        }

        @Test
        @DisplayName("a reading above the tenant's BFM maximum is refused and nothing is written")
        void rejectsAReadingAboveTheMaximum() {
            when(manualReadingMaxValues.maxFor(17, ReadingChannel.BFM)).thenReturn(Optional.of(new BigDecimal("550")));

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, new BigDecimal("600"), null, CALLER))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getReason()).isEqualTo("Reading can't be more than 550 m³.");
                    });
            verify(telemetryTenantRepository, never())
                    .updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any(), any());
            verify(readingRepublisher, never()).republish(any(), any(), any());
        }

        @Test
        @DisplayName("a reading at the tenant's BFM maximum is written")
        void acceptsAReadingAtTheMaximum() {
            when(manualReadingMaxValues.maxFor(17, ReadingChannel.BFM)).thenReturn(Optional.of(new BigDecimal("600")));

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, new BigDecimal("600"), null, CALLER);

            verify(telemetryTenantRepository).updateConfirmedReading(SCHEMA, 100L, new BigDecimal("600"), 11L,
                    RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WEB);
        }

        @Test
        void propagatesAnUnexpectedRepositoryFailure() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                    .thenThrow(new IllegalStateException("connection reset"));

            assertThatThrownBy(() -> service.updateYesterdayFinalReadingBySchemeId(
                    SCHEME_ID, BigDecimal.TEN, null, CALLER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("connection reset");
        }
    }
}
