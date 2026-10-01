package org.arghyam.jalsoochak.analytics.controller.scheme;

import org.arghyam.jalsoochak.analytics.entity.DimScheme;
import org.arghyam.jalsoochak.analytics.repository.DimSchemeRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /schemes}, called directly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AnalyticsSchemeStatusController — direct invocation")
class AnalyticsSchemeStatusControllerUnitTest {

    private static final int TENANT = 1;

    @Mock
    private FactSchemePerformanceRepository schemePerformanceRepository;
    @Mock
    private SchemeRegularityService schemeRegularityService;
    @Mock
    private DimSchemeRepository dimSchemeRepository;

    @InjectMocks
    private AnalyticsSchemeStatusController controller;

    @Nested
    @DisplayName("GET /schemes")
    class Schemes {

        @Test
        void scopesToATenantWhenOneIsGiven() {
            List<DimScheme> schemes = List.of(new DimScheme());
            when(dimSchemeRepository.findByTenantId(TENANT)).thenReturn(schemes);

            assertThat(controller.getSchemes(TENANT).getBody().getData()).isSameAs(schemes);
            verify(dimSchemeRepository, never()).findAll();
        }

        @Test
        void returnsEverySchemeWhenNoTenantIsGiven() {
            List<DimScheme> schemes = List.of(new DimScheme());
            when(dimSchemeRepository.findAll()).thenReturn(schemes);

            assertThat(controller.getSchemes(null).getBody().getData()).isSameAs(schemes);
        }

        @Test
        void answersFiveHundredWhenTheLookupFails() {
            when(dimSchemeRepository.findAll()).thenThrow(new IllegalStateException("db down"));

            assertThat(controller.getSchemes(null).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
