package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityFixtures;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A channel declared on the submission wins over the operator's stored preference; no declaration
 * leaves the existing preference lookup exactly as it was.
 */
@ExtendWith(MockitoExtension.class)
class BfmReadingServiceDeclaredChannelTest {

    private static final String SCHEMA = "tenant_test";
    private static final long SCHEME_ID = 10L;
    private static final long OPERATOR_ID = 1L;
    private static final int TENANT_ID = 1;
    private static final String CONTACT = "919999999999";
    private static final long READING_ID = 99L;

    @Mock
    private TelemetryTenantRepository repo;
    @Mock
    private FlowVisionService flowVisionService;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private GlificOperatorContextService glificOperatorContextService;
    @Mock
    private ReadingChannelResolver readingChannelResolver;

    private BfmReadingService service;
    private final TelemetryOperator operator =
            new TelemetryOperator(OPERATOR_ID, TENANT_ID, "op", "op@example.com", CONTACT, null);

    @BeforeEach
    void setUp() {
        service = new BfmReadingService(
                repo,
                flowVisionService,
                telemetryEventPublisher,
                tenantConfigRepository,
                new ObjectMapper(),
                glificOperatorContextService,
                null,
                readingChannelResolver,
                new RolloverResolutionService(true, new ObjectMapper()),
                SupplyPlausibilityFixtures.guard(
                        SupplyPlausibilityProperties.Mode.AUDIT, repo, tenantConfigRepository),
                null,
                null);
        lenient().when(repo.existsSchemeById(SCHEMA, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findOperatorById(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(operator));
        lenient().when(repo.isOperatorMappedToScheme(SCHEMA, OPERATOR_ID, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME_ID, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(
                        new BigDecimal("140"), ReadingTime.now().minusDays(1))));
        lenient().when(repo.findLatestPlaceholderFlowReadingIdForDate(eq(SCHEMA), eq(SCHEME_ID), eq(OPERATOR_ID),
                any(LocalDate.class))).thenReturn(Optional.empty());
        lenient().when(repo.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(), any(),
                any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(READING_ID);
    }

    @Test
    @DisplayName("a declared channel is persisted on the reading and never looked up from the preference")
    void declaredChannelWinsOverTheStoredPreference() {
        service.createReading(requestWithChannel(ReadingChannel.PDU), SCHEMA, operator, CONTACT, false);

        verify(repo).updateFlowReadingChannel(SCHEMA, READING_ID, "PDU");
        verify(readingChannelResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("a declared channel is published on the event as its numeric code")
    void declaredChannelIsPublishedOnTheEvent() {
        service.createReading(requestWithChannel(ReadingChannel.ELM), SCHEMA, operator, CONTACT, false);

        verify(telemetryEventPublisher).publishMeterReadingRecorded(eq(TENANT_ID), eq(SCHEME_ID),
                eq(OPERATOR_ID), isNull(), eq(new BigDecimal("150")), isNull(), isNull(),
                any(LocalDateTime.class), eq(ReadingChannel.ELM.getCode()), any(LocalDate.class),
                eq(1), eq(0));
    }

    @Test
    @DisplayName("declaring BFM explicitly behaves exactly like resolving to BFM")
    void declaringBfmIsHonouredToo() {
        service.createReading(requestWithChannel(ReadingChannel.BFM), SCHEMA, operator, CONTACT, false);

        verify(repo).updateFlowReadingChannel(SCHEMA, READING_ID, "BFM");
        verify(readingChannelResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("without a declared channel the operator's stored preference still decides")
    void noDeclaredChannelFallsBackToThePreference() {
        when(readingChannelResolver.resolve(TENANT_ID, CONTACT)).thenReturn(ReadingChannel.MAN);

        service.createReading(requestWithChannel(null), SCHEMA, operator, CONTACT, false);

        verify(readingChannelResolver).resolve(TENANT_ID, CONTACT);
        verify(repo).updateFlowReadingChannel(SCHEMA, READING_ID, "MAN");
    }

    @Test
    @DisplayName("without a declared channel and with no preference on record the reading stays BFM")
    void noDeclaredChannelAndNoPreferenceStaysBfm() {
        when(readingChannelResolver.resolve(TENANT_ID, CONTACT)).thenReturn(ReadingChannel.BFM);

        service.createReading(requestWithChannel(null), SCHEMA, operator, CONTACT, false);

        verify(repo).updateFlowReadingChannel(SCHEMA, READING_ID, "BFM");
    }

    private static CreateReadingRequest requestWithChannel(ReadingChannel channel) {
        return CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingValue(new BigDecimal("150"))
                .externallyAsserted(true)
                .declaredChannel(channel)
                .build();
    }
}
