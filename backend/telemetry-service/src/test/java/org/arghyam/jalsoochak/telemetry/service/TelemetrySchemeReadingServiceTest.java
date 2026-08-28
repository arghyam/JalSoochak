package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.config.TenantContext;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryCompletedFlowReading;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Back-office correction of a scheme's already-submitted final reading.
 *
 * <p>The endpoint has no JWT: authorisation is phone-number → user → scheme-mapping, so the reject
 * paths matter as much as the happy path. A correction also re-publishes the derived water quantity
 * for the corrected day and for the day after it, since both deltas move.</p>
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
    private TelemetryEventPublisher telemetryEventPublisher;

    @InjectMocks
    private TelemetrySchemeReadingService service;

    private static TelemetryCompletedFlowReading reading(Long id, LocalDate day, String confirmed, Long createdBy) {
        return new TelemetryCompletedFlowReading(id, "corr-" + id, createdBy, day,
                confirmed == null ? null : new BigDecimal(confirmed));
    }

    @BeforeEach
    void setUp() {
        TenantContext.setSchema(SCHEMA);
        when(telemetryTenantRepository.findUserIdByPhone(SCHEMA, PHONE)).thenReturn(Optional.of(11L));
        when(telemetryTenantRepository.findTenantIdBySchemaName(SCHEMA)).thenReturn(17);
        when(telemetryTenantRepository.isUserMappedToScheme(SCHEMA, 11L, SCHEME_ID)).thenReturn(true);
        when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                .thenReturn(Optional.of(reading(100L, TARGET_DAY, "500", 22L)));
        when(telemetryTenantRepository.findPreviousFlowReadingForScheme(anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findEarliestCompletedFlowReadingAfterDateForScheme(
                anyString(), anyLong(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
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
                    .updateConfirmedReading(anyString(), anyLong(), any(), anyLong());
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
    @DisplayName("correction and derived water quantity")
    class Correction {

        @Test
        void writesTheCorrectedReadingAgainstTheRequestingUser() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            verify(telemetryTenantRepository)
                    .updateConfirmedReading(SCHEMA, 100L, new BigDecimal("600"), 11L);
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
        void publishesTheFullCorrectedReadingAsTheDaysQuantityWhenThereIsNoPriorReading() {
            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            verify(telemetryEventPublisher).publishWaterQuantityRecorded(
                    eq(17), eq(SCHEME_ID), eq(22L), eq(TARGET_DAY), eq(new BigDecimal("600")), eq(1));
        }

        @Test
        void subtractsThePriorReadingWhenComputingTheDaysQuantity() {
            when(telemetryTenantRepository.findPreviousFlowReadingForScheme(SCHEMA, 100L))
                    .thenReturn(Optional.of(reading(99L, TARGET_DAY.minusDays(1), "450", 22L)));

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            verify(telemetryEventPublisher).publishWaterQuantityRecorded(
                    eq(17), eq(SCHEME_ID), eq(22L), eq(TARGET_DAY), eq(new BigDecimal("150")), eq(1));
        }

        @Test
        void alsoRepublishesTheFollowingDayBecauseItsDeltaMoved() {
            when(telemetryTenantRepository.findPreviousFlowReadingForScheme(SCHEMA, 100L))
                    .thenReturn(Optional.of(reading(99L, TARGET_DAY.minusDays(1), "450", 22L)));
            when(telemetryTenantRepository.findEarliestCompletedFlowReadingAfterDateForScheme(
                    SCHEMA, SCHEME_ID, TARGET_DAY))
                    .thenReturn(Optional.of(reading(101L, TARGET_DAY.plusDays(1), "800", 33L)));

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            verify(telemetryEventPublisher).publishWaterQuantityRecorded(
                    eq(17), eq(SCHEME_ID), eq(22L), eq(TARGET_DAY), eq(new BigDecimal("150")), eq(1));
            verify(telemetryEventPublisher).publishWaterQuantityRecorded(
                    eq(17), eq(SCHEME_ID), eq(33L), eq(TARGET_DAY.plusDays(1)),
                    eq(new BigDecimal("200")), eq(1));
        }

        @Test
        void attributesTheEventToTheCorrectingUserWhenTheReadingHasNoAuthor() {
            when(telemetryTenantRepository.findLatestCompletedFlowReadingForScheme(SCHEMA, SCHEME_ID))
                    .thenReturn(Optional.of(reading(100L, TARGET_DAY, "500", null)));

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            verify(telemetryEventPublisher).publishWaterQuantityRecorded(
                    eq(17), eq(SCHEME_ID), eq(11L), eq(TARGET_DAY), any(), eq(1));
        }

        @Test
        void attributesTheFollowingDaysEventToTheTargetAuthorWhenItHasNone() {
            when(telemetryTenantRepository.findEarliestCompletedFlowReadingAfterDateForScheme(
                    SCHEMA, SCHEME_ID, TARGET_DAY))
                    .thenReturn(Optional.of(reading(101L, TARGET_DAY.plusDays(1), "800", null)));

            service.updateYesterdayFinalReadingBySchemeId(SCHEME_ID, PHONE, new BigDecimal("600"), null);

            verify(telemetryEventPublisher, times(2)).publishWaterQuantityRecorded(
                    eq(17), eq(SCHEME_ID), eq(22L), any(), any(), eq(1));
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
