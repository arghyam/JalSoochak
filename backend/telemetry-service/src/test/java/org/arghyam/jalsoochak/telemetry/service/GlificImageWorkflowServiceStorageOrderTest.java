package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When the meter image is fetched and when it reaches object storage.
 *
 * <p>Both used to happen the moment the webhook arrived, before the submitter had been resolved — so
 * every rejected submission still made this service dial a caller-supplied URL, and still wrote an
 * object, under a key built from the caller-supplied contact id, onto a bucket that is anonymously
 * readable. Doing neither until the submission is known to belong to a mapped operator removes both
 * without changing anything a legitimate operator sees.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificImageWorkflowService — when the image is fetched and stored")
class GlificImageWorkflowServiceStorageOrderTest {

    private static final String CONTACT_ID = "919876543210";
    private static final byte[] IMAGE = {1, 2, 3};

    @Mock
    private GlificMediaService glificMediaService;
    @Mock
    private BfmReadingService bfmReadingService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private GlificOperatorContextService operatorContextService;
    @Mock
    private GlificLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private GlificImageWorkflowService service;

    private static GlificWebhookRequest submission() {
        return GlificWebhookRequest.builder()
                .contactId(CONTACT_ID)
                .mediaUrl("https://media.glific.example/meter.jpg")
                .build();
    }

    private static TelemetryOperatorWithSchema mappedOperator() {
        return new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(11L, 22, "name", "name@example.com", CONTACT_ID, null));
    }

    private void imageDownloads() throws Exception {
        when(glificMediaService.downloadImage(null, "https://media.glific.example/meter.jpg")).thenReturn(IMAGE);
        when(localizationService.resolveLanguageKeyForContact(anyString())).thenReturn("english");
        when(localizationService.resolveUserFacingErrorMessage(any(), anyString(), anyString()))
                .thenReturn("Image could not be processed.");
    }

    @Test
    void fetchesAndStoresNothingWhenTheSubmitterIsNotAKnownOperator() throws Exception {
        imageDownloads();
        when(operatorContextService.resolveOperatorWithSchema(CONTACT_ID))
                .thenThrow(new IllegalStateException("Operator not found"));

        CreateReadingResponse response = service.processImage(submission());

        assertThat(response.isSuccess()).isFalse();
        verify(glificMediaService, never()).downloadImage(any(), any());
        verify(glificMediaService, never()).uploadImage(anyString(), any());
    }

    @Test
    void fetchesAndStoresNothingWhenTheOperatorIsMappedToNoScheme() throws Exception {
        imageDownloads();
        when(operatorContextService.resolveOperatorWithSchema(CONTACT_ID)).thenReturn(mappedOperator());
        when(operatorContextService.resolveOperatorLanguage(any(), any())).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(anyString(), any(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findFirstSchemeForUser(anyString(), any())).thenReturn(Optional.empty());

        CreateReadingResponse response = service.processImage(submission());

        assertThat(response.isSuccess()).isFalse();
        verify(glificMediaService, never()).downloadImage(any(), any());
        verify(glificMediaService, never()).uploadImage(anyString(), any());
    }

    @Test
    void fetchesStoresAndRecordsTheImageUrlForAMappedOperator() throws Exception {
        imageDownloads();
        when(operatorContextService.resolveOperatorWithSchema(CONTACT_ID)).thenReturn(mappedOperator());
        when(operatorContextService.resolveOperatorLanguage(any(), any())).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(anyString(), any(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findFirstSchemeForUser(anyString(), any())).thenReturn(Optional.of(101L));
        when(glificMediaService.uploadImage(CONTACT_ID, IMAGE)).thenReturn("https://minio/bfm/x.jpg");
        when(bfmReadingService.createReading(any(), anyString(), any(), anyString(), anyBoolean(), any()))
                .thenReturn(CreateReadingResponse.builder().success(true).message("ok").build());
        when(localizationService.localizeMessage("ok", "english")).thenReturn("ok");

        CreateReadingResponse response = service.processImage(submission());

        assertThat(response.isSuccess()).isTrue();
        verify(glificMediaService).downloadImage(null, "https://media.glific.example/meter.jpg");
        verify(glificMediaService).uploadImage(CONTACT_ID, IMAGE);

        ArgumentCaptor<CreateReadingRequest> captor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(captor.capture(), anyString(), any(), anyString(), anyBoolean(), any());
        assertThat(captor.getValue().getReadingUrl()).isEqualTo("https://minio/bfm/x.jpg");
    }
}
