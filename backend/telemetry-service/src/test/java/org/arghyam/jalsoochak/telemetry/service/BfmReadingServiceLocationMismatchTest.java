package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.provider.ocr.flowvision.FlowVisionOcrExtractor;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSupplyCounts;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.location.LocationAffinityService;
import org.arghyam.jalsoochak.telemetry.service.location.LocationVerdict;
import org.arghyam.jalsoochak.telemetry.service.location.ReadingSubmission;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LOCATION-AFFINITY inside {@code createReading}, the funnel both the WhatsApp image path and the
 * state-IT reading API pass through.
 *
 * <p>The properties pinned here are the ones that make this feature safe to ship: the reading is
 * never rejected, refused or reshaped by a boundary mismatch, and the two channels reach the check
 * by different routes — coordinates on the request for the API, coordinates already on the reused
 * placeholder row for WhatsApp.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BfmReadingService — location mismatch")
class BfmReadingServiceLocationMismatchTest {

    private static final String SCHEMA = "tenant_as";
    private static final long SCHEME_ID = 10L;
    private static final long OPERATOR_ID = 7L;
    private static final int TENANT_ID = 1;
    private static final String CONTACT = "919999999999";
    private static final long READING_ID = 99L;
    private static final LocalDateTime READING_AT = LocalDateTime.of(2026, 9, 8, 9, 30);
    private static final BigDecimal BASELINE = new BigDecimal("900");
    private static final LocalDateTime BASELINE_AT = LocalDateTime.of(2026, 9, 7, 9, 0);

    private static final BigDecimal LAT = new BigDecimal("26.1553");
    private static final BigDecimal LNG = new BigDecimal("91.7362");

    @Mock
    private TelemetryTenantRepository repo;
    @Mock
    private FlowVisionOcrExtractor flowVisionOcrExtractor;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private ReadingChannelResolver readingChannelResolver;
    @Mock
    private LocationAffinityService locationAffinityService;

    private final TelemetryOperator operator =
            new TelemetryOperator(OPERATOR_ID, TENANT_ID, "op", "op@example.com", CONTACT, null);

    private BfmReadingService service;

    @BeforeEach
    void setUp() {
        lenient().when(repo.existsSchemeById(SCHEMA, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findOperatorById(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(operator));
        lenient().when(repo.isOperatorMappedToScheme(SCHEMA, OPERATOR_ID, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME_ID, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(BASELINE, BASELINE_AT)));
        lenient().when(readingChannelResolver.resolve(any(), any())).thenReturn(ReadingChannel.BFM);
        lenient().when(tenantConfigRepository.findConfigValue(anyInt(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(repo.findLatestPlaceholderFlowReadingIdForDate(
                eq(SCHEMA), eq(SCHEME_ID), eq(OPERATOR_ID), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        lenient().when(repo.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                any(), any(), any(), anyInt(), any(), any(), any(), any(), any())).thenReturn(READING_ID);
        lenient().when(repo.createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                any(BigDecimal.class), any(BigDecimal.class), anyString(), any(), any(), any()))
                .thenReturn(READING_ID);
        lenient().when(locationAffinityService.recordMismatchIfAny(
                anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LocationVerdict.Outside(1201.0d, 500.0d));

        service = new BfmReadingService(
                repo, flowVisionOcrExtractor, telemetryEventPublisher, tenantConfigRepository,
                new ObjectMapper(), operatorContextService, null, readingChannelResolver,
                new RolloverResolutionService(false, new ObjectMapper()),
                SupplyPlausibilityFixtures.guard(
                        SupplyPlausibilityProperties.Mode.AUDIT, repo, tenantConfigRepository),
                null,
                null,
                locationAffinityService);
    }

    private CreateReadingRequest submission(BigDecimal latitude, BigDecimal longitude) {
        return CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingValue(new BigDecimal("950"))
                .externallyAsserted(true)
                .readingTime(READING_AT)
                .latitude(latitude)
                .longitude(longitude)
                .build();
    }

    private CreateReadingResponse submit(BigDecimal latitude, BigDecimal longitude) {
        return service.createReading(submission(latitude, longitude), SCHEMA, operator, CONTACT, false);
    }

    @Test
    @DisplayName("the reading is still stored when the submission is out of bounds")
    void theReadingIsStillStored() {
        CreateReadingResponse response = submit(LAT, LNG);

        // The whole point: unlike every other anomaly type, this one observes rather than rejects.
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getQualityStatus()).isNotEqualTo("REJECTED");
        assertThat(response.getErrorCode()).isNull();
        verify(repo).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                any(), any(), any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the check is handed the stored row and its correlation id, not just the operator")
    void theCheckIsGivenTheSubmission() {
        submit(LAT, LNG);

        ArgumentCaptor<ReadingSubmission> submission = ArgumentCaptor.forClass(ReadingSubmission.class);
        verify(locationAffinityService).recordMismatchIfAny(
                eq(SCHEMA), eq(TENANT_ID), eq(OPERATOR_ID), eq(SCHEME_ID), submission.capture(),
                eq(LAT), eq(LNG), any());

        // ANOMALY-SUBMISSION-LINK: both identifiers, because the tenant row and the warehouse row
        // join on different ones.
        assertThat(submission.getValue().readingId()).isEqualTo(READING_ID);
        assertThat(submission.getValue().correlationId()).isNotBlank();
        assertThat(submission.getValue().readingDate()).isEqualTo(READING_AT.toLocalDate());
    }

    @Test
    @DisplayName("coordinates on the request tag the state-IT path")
    void requestCoordinatesTagTheStateApi() {
        submit(LAT, LNG);

        verify(locationAffinityService).recordMismatchIfAny(
                anyString(), any(), any(), any(), any(), eq(LAT), eq(LNG),
                eq(LocationAffinityService.Path.STATE_API));
    }

    @Test
    @DisplayName("no coordinates on the request tags the WhatsApp image path, which reads them off the row")
    void absentCoordinatesTagTheImagePath() {
        submit(null, null);

        // The service resolves them from the reused placeholder row; createReading passes nulls and
        // lets it, which is what keeps one check point for both channels.
        verify(locationAffinityService).recordMismatchIfAny(
                anyString(), any(), any(), any(), any(), isNull(), isNull(),
                eq(LocationAffinityService.Path.IMAGE_SUBMISSION));
    }

    @Test
    @DisplayName("coordinates on the request are written onto the reading row")
    void requestCoordinatesArePersisted() {
        submit(LAT, LNG);

        // The anomaly discloses a distance that has to stay recomputable from the stored reading, and
        // the state-IT path is the only one whose coordinates arrive on the request rather than on a
        // placeholder row /location already wrote.
        verify(repo).updateReadingLocation(SCHEMA, READING_ID, LAT, LNG, OPERATOR_ID);
    }

    @Test
    @DisplayName("no coordinates on the request leaves whatever the row already carries")
    void absentCoordinatesLeaveTheRowAlone() {
        submit(null, null);

        // The WhatsApp path's coordinates are already on the reused placeholder row; blanking them
        // here would destroy the only copy.
        verify(repo, never()).updateReadingLocation(anyString(), anyLong(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("a failed coordinate write does not lose the reading")
    void aFailedCoordinateWriteDoesNotLoseTheReading() {
        // The row is already stored by this point and createReading is not transactional here, so the
        // annotation is best-effort: an older tenant schema missing the column must not cost a reading.
        doThrow(new IllegalStateException("Missing required column tenant_as.flow_reading_table.latitude"))
                .when(repo).updateReadingLocation(anyString(), anyLong(), any(), any(), anyLong());

        CreateReadingResponse response = submit(LAT, LNG);

        assertThat(response.isSuccess()).isTrue();
        verify(locationAffinityService).recordMismatchIfAny(
                anyString(), any(), any(), any(), any(), eq(LAT), eq(LNG), any());
    }

    @Test
    @DisplayName("a quarantined reading is still boundary-checked, so both anomalies are recorded")
    void quarantineDoesNotSuppressTheBoundaryCheck() {
        // The quarantine branch returns early. The check is deliberately placed before it: a reading
        // can be both implausibly high and taken from the wrong place, and suppressing one because
        // of the other would lose a real signal.
        when(repo.supportsQuarantine(SCHEMA)).thenReturn(true);
        when(repo.findLatestConfirmedReadingSnapshotBeforeDate(
                eq(SCHEMA), eq(SCHEME_ID), any(LocalDate.class), isNull()))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(BASELINE, BASELINE_AT)));
        when(repo.findSchemeSupplyCounts(SCHEMA, SCHEME_ID))
                .thenReturn(Optional.of(new TelemetrySchemeSupplyCounts(1, 0, 0)));

        CreateReadingRequest implausible = CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingValue(new BigDecimal("99999"))
                .externallyAsserted(true)
                .supplyPlausibilityChecked(true)
                .readingTime(READING_AT)
                .latitude(LAT)
                .longitude(LNG)
                .build();

        service.createReading(implausible, SCHEMA, operator, CONTACT, false);

        verify(locationAffinityService).recordMismatchIfAny(
                anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a null collaborator simply skips the check rather than losing the reading")
    void aNullCollaboratorIsTolerated() {
        // Matches how the OCR collaborators are treated: a unit test that does not exercise the
        // boundary check may pass null, and the reading must still go through.
        BfmReadingService withoutCheck = new BfmReadingService(
                repo, flowVisionOcrExtractor, telemetryEventPublisher, tenantConfigRepository,
                new ObjectMapper(), operatorContextService, null, readingChannelResolver,
                new RolloverResolutionService(false, new ObjectMapper()),
                SupplyPlausibilityFixtures.guard(
                        SupplyPlausibilityProperties.Mode.AUDIT, repo, tenantConfigRepository),
                null, null, null);

        CreateReadingResponse response = withoutCheck.createReading(
                submission(LAT, LNG), SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a reading inside the boundary is answered exactly as before this feature")
    void withinChangesNothing() {
        when(locationAffinityService.recordMismatchIfAny(
                anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LocationVerdict.Within(111.0d, 500.0d));

        CreateReadingResponse response = submit(LAT, LNG);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.isLocationMismatch()).isFalse();
    }
}
