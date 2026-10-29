package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterImageWebhookRequest;
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
@DisplayName("MeterImageWorkflowService — when the image is fetched and stored")
class MeterImageWorkflowServiceStorageOrderTest {

    private static final String CONTACT_ID = "919876543210";
    private static final byte[] IMAGE = {1, 2, 3};

    @Mock
    private InboundMediaService inboundMediaService;
    @Mock
    private BfmReadingService bfmReadingService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private ConversationLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;
    @Mock
    private ConversationSchemeResolver conversationSchemeResolver;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MeterImageWorkflowService service;

    private static MeterImageWebhookRequest submission() {
        return MeterImageWebhookRequest.builder()
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
        when(inboundMediaService.downloadImage(null, "https://media.glific.example/meter.jpg")).thenReturn(IMAGE);
        when(localizationService.resolveLanguageKeyForContact(anyString())).thenReturn("english");
        when(localizationService.resolveUserFacingErrorMessage(any(), any(), anyString()))
                .thenReturn("Image could not be processed.");
    }

    @Test
    void fetchesAndStoresNothingWhenTheSubmitterIsNotAKnownOperator() throws Exception {
        imageDownloads();
        when(operatorContextService.resolveOperatorWithSchema(CONTACT_ID))
                .thenThrow(new IllegalStateException("Operator not found"));

        CreateReadingResponse response = service.processImage(submission());

        assertThat(response.isSuccess()).isFalse();
        verify(inboundMediaService, never()).downloadImage(any(), any());
        verify(inboundMediaService, never()).uploadImage(anyString(), any());
    }

    @Test
    void fetchesAndStoresNothingWhenTheOperatorIsMappedToNoScheme() throws Exception {
        imageDownloads();
        when(operatorContextService.resolveOperatorWithSchema(CONTACT_ID)).thenReturn(mappedOperator());
        when(operatorContextService.resolveOperatorLanguage(any(), any())).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(conversationSchemeResolver.require(mappedOperator()))
                .thenThrow(new IllegalStateException(ConversationSchemeResolver.NOT_MAPPED_MESSAGE));

        CreateReadingResponse response = service.processImage(submission());

        assertThat(response.isSuccess()).isFalse();
        verify(inboundMediaService, never()).downloadImage(any(), any());
        verify(inboundMediaService, never()).uploadImage(anyString(), any());
    }

    @Test
    void fetchesStoresAndRecordsTheImageUrlForAMappedOperator() throws Exception {
        imageDownloads();
        when(operatorContextService.resolveOperatorWithSchema(CONTACT_ID)).thenReturn(mappedOperator());
        when(operatorContextService.resolveOperatorLanguage(any(), any())).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(conversationSchemeResolver.require(mappedOperator())).thenReturn(101L);
        when(inboundMediaService.uploadImage(CONTACT_ID, IMAGE)).thenReturn("https://storage.example.org/bfm/x.jpg");
        when(bfmReadingService.createReading(any(), anyString(), any(), anyString(), anyBoolean(), any()))
                .thenReturn(CreateReadingResponse.builder().success(true).message("ok").build());
        when(localizationService.localizeMessage("ok", "english")).thenReturn("ok");

        CreateReadingResponse response = service.processImage(submission());

        assertThat(response.isSuccess()).isTrue();
        verify(inboundMediaService).downloadImage(null, "https://media.glific.example/meter.jpg");
        verify(inboundMediaService).uploadImage(CONTACT_ID, IMAGE);

        ArgumentCaptor<CreateReadingRequest> captor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(captor.capture(), anyString(), any(), anyString(), anyBoolean(), any());
        assertThat(captor.getValue().getReadingUrl()).isEqualTo("https://storage.example.org/bfm/x.jpg");
    }
}
