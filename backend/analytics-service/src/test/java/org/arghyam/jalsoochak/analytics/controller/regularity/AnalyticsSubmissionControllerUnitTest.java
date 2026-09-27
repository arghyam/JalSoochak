package org.arghyam.jalsoochak.analytics.controller.regularity;

import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /meter-readings}, called directly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AnalyticsSubmissionController — direct invocation")
class AnalyticsSubmissionControllerUnitTest {

    private static final int TENANT = 1;
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 2, 28);

    @Mock
    private SchemeRegularityService schemeRegularityService;
    @Mock
    private FactMeterReadingRepository meterReadingRepository;
    @Mock
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;
    @Mock
    private AuthenticatedRequestContextService authenticatedRequestContextService;

    @InjectMocks
    private AnalyticsSubmissionController controller;

    @BeforeEach
    void stubDefaults() {
        when(defaultAnalyticsDateWindowProvider.defaultWindow())
                .thenReturn(new DefaultAnalyticsDateWindowProvider.DateWindow(START, END));
    }

    @Nested
    @DisplayName("GET /meter-readings")
    class MeterReadings {

        /**
         * This endpoint is descending-ordered: it validates {@code start_date} is strictly AFTER
         * {@code end_date}, and its default window is applied in that same reversed order. Pinned here
         * because the parameter names read the other way round.
         */
        @Test
        void requiresStartDateStrictlyAfterEndDate() {
            List<FactMeterReading> readings = List.of(new FactMeterReading());
            when(meterReadingRepository.findByTenantIdAndSchemeIdAndReadingDateBetween(
                    TENANT, 7, END, START)).thenReturn(readings);

            var response = controller.getMeterReadings(TENANT, 7, END, START);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isSameAs(readings);
        }

        @Test
        void rejectsAnAscendingWindow() {
            assertThat(controller.getMeterReadings(TENANT, 7, START, END).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsEqualDates() {
            assertThat(controller.getMeterReadings(TENANT, 7, START, START).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void appliesTheDefaultWindowReversed() {
            controller.getMeterReadings(TENANT, 7, null, null);

            verify(meterReadingRepository)
                    .findByTenantIdAndSchemeIdAndReadingDateBetween(TENANT, 7, END, START);
        }

        @Test
        void rejectsAHalfSuppliedDateWindow() {
            assertThat(controller.getMeterReadings(TENANT, 7, END, null).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.getMeterReadings(TENANT, 7, null, START).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void answersFiveHundredWhenTheLookupFails() {
            when(meterReadingRepository.findByTenantIdAndSchemeIdAndReadingDateBetween(
                    anyInt(), anyInt(), any(), any())).thenThrow(new IllegalStateException("db down"));

            assertThat(controller.getMeterReadings(TENANT, 7, END, START).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
