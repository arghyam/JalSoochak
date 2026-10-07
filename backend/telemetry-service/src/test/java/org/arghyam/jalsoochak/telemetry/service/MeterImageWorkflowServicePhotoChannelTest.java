package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterImageWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A WhatsApp operator whose channel can't read photos (ELM until it has an OCR model, PDU always) gets the
 * rejection in their own language, with its error code.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MeterImageWorkflowService — a photo from an operator whose channel can't read one")
class MeterImageWorkflowServicePhotoChannelTest {

    private static final String CONTACT_ID = "91XXXXXXXXXX";
    private static final String SCHEMA = "tenant_test";
    private static final String STORED_URL = "https://storage.example.org/bfm/x.jpg";
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
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;

    private MeterImageWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new MeterImageWorkflowService(
                inboundMediaService,
                bfmReadingService,
                telemetryTenantRepository,
                operatorContextService,
                new ConversationLocalizationService(operatorContextService),
                tenantConfigRepository,
                userChannelPreferenceRepository,
                new ObjectMapper());
    }

    @ParameterizedTest(name = "language={0}")
    @CsvSource({
            "English, Meter photos are not supported for your reading channel.",
            "हिंदी, आपके रीडिंग चैनल के लिए मीटर की फ़ोटो समर्थित नहीं है।"
    })
    void repliesWithTheLocalisedRejection(String language, String expectedMessage) throws Exception {
        TelemetryOperatorWithSchema operator = new TelemetryOperatorWithSchema(
                SCHEMA, new TelemetryOperator(11L, 22, "name", "name@example.com", CONTACT_ID, null));
        when(operatorContextService.resolveOperatorWithSchema(CONTACT_ID)).thenReturn(operator);
        when(operatorContextService.resolveOperatorLanguage(operator, 22)).thenReturn(language);
        when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(eq(SCHEMA), eq(11L), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, 11L)).thenReturn(Optional.of(101L));
        when(inboundMediaService.downloadImage(null, "https://media.glific.example/meter.jpg")).thenReturn(IMAGE);
        when(inboundMediaService.uploadImage(CONTACT_ID, IMAGE)).thenReturn(STORED_URL);
        when(bfmReadingService.createReading(any(), eq(SCHEMA), any(), eq(CONTACT_ID), eq(false), eq(OcrRetryMode.RESILIENT)))
                .thenReturn(CreateReadingResponse.builder()
                        .success(false)
                        .qualityStatus("REJECTED")
                        .errorCode(TelemetryErrorCode.IMAGE_NOT_SUPPORTED_FOR_CHANNEL)
                        .message("Meter photos are not supported for your reading channel.")
                        .build());

        CreateReadingResponse response = service.processImage(MeterImageWebhookRequest.builder()
                .contactId(CONTACT_ID)
                .mediaUrl("https://media.glific.example/meter.jpg")
                .build());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.IMAGE_NOT_SUPPORTED_FOR_CHANNEL);
        assertThat(response.getMessage()).isEqualTo(expectedMessage);
    }
}
