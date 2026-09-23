package org.arghyam.jalsoochak.telemetry.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.arghyam.jalsoochak.telemetry.config.WebhookRoute;
import org.arghyam.jalsoochak.telemetry.controller.ingest.MultiFormatReadingController;
import org.arghyam.jalsoochak.telemetry.controller.ingest.ReadingIngestController;
import org.arghyam.jalsoochak.telemetry.controller.ingest.TelemetryValidationExceptionHandler;
import org.arghyam.jalsoochak.telemetry.controller.webhook.ConversationWebhookController;
import org.arghyam.jalsoochak.telemetry.controller.webhook.IssueReportWebhookController;
import org.arghyam.jalsoochak.telemetry.controller.webhook.MeterChangeWebhookController;
import org.arghyam.jalsoochak.telemetry.controller.webhook.ReadingWebhookController;
import org.arghyam.jalsoochak.telemetry.controller.webhook.SelectionWebhookController;
import org.arghyam.jalsoochak.telemetry.controller.webhook.WebhookValidationExceptionHandler;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.ingest.CanonicalReadingRequestMapper;
import org.arghyam.jalsoochak.telemetry.ingest.ReadingRequestMapperRegistry;
import org.arghyam.jalsoochak.telemetry.service.BfmReadingService;
import org.arghyam.jalsoochak.telemetry.service.ConversationMessageService;
import org.arghyam.jalsoochak.telemetry.service.ConversationSelectionService;
import org.arghyam.jalsoochak.telemetry.service.MeterImageWorkflowService;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingConversationService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.arghyam.jalsoochak.telemetry.service.WelcomeMessageService;
import org.arghyam.jalsoochak.telemetry.validation.ReadingUrlTestValidation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.ControllerAdviceBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins which exception advice answers for which controller.
 *
 * <p>telemetry-service has no global advice, so these two scoped bindings are the whole error
 * contract: a controller neither advice covers falls through to Spring's defaults. Losing a binding
 * is silent — the happy path is untouched and every endpoint test still passes:
 * <ul>
 *   <li>a webhook controller outside the webhook advice answers a validation failure in Boot's
 *       default shape instead of the {@code {success, message}} envelope the chatbot flow parses;</li>
 *   <li>the canonical ingestion controller outside the ingest advice stops publishing
 *       {@code submissionRejected} on a validation reject, so analytics undercounts reported
 *       schemes.</li>
 * </ul>
 * {@code MultiFormatReadingController} is deliberately covered by neither.
 *
 * <p>The binding checks use Spring's own {@link ControllerAdviceBean} applicability test over every
 * controller in the service, so they cover a controller no HTTP case can reach (most webhook request
 * types carry no constraint that could trigger the advice). The HTTP cases then prove the binding
 * end to end, through a {@code MockMvc} holding every controller and both advices at once.
 * {@code standaloneSetup} rather than {@code @WebMvcTest} for the reason given in
 * {@code MultiFormatReadingControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Controller advice bindings")
class ControllerAdviceBindingTest {

    private static final String SERVICE_PACKAGE = "org.arghyam.jalsoochak.telemetry";
    private static final String TELEMETRY = "/api/v1/telemetry";
    private static final String UNREADABLE_BODY = "{\"state_scheme_id\": ";

    private static GenericApplicationContext adviceContext;
    private static List<ControllerAdviceBean> adviceBeans;

    @Mock
    private MeterImageWorkflowService imageWorkflowService;
    @Mock
    private MeterReadingConversationService meterWorkflowService;
    @Mock
    private ConversationSelectionService selectionService;
    @Mock
    private ConversationMessageService messageService;
    @Mock
    private TelemetryApiKeyService apiKeyService;
    @Mock
    private BfmReadingService bfmReadingService;
    @Mock
    private TelemetrySubmissionAuditService auditService;
    @Mock
    private TelemetryEventPublisher eventPublisher;
    @Mock
    private WelcomeMessageService welcomeMessageService;

    private MockMvc mockMvc;

    /**
     * Registers the advices in a bare context and lets Spring discover them exactly as the dispatcher
     * does. Discovery sorts them, which instantiates each one, so they are registered with explicit
     * constructors rather than scanned.
     */
    @BeforeAll
    static void discoverAdvices() {
        adviceContext = new GenericApplicationContext();
        adviceContext.registerBean(WebhookValidationExceptionHandler.class,
                WebhookValidationExceptionHandler::new);
        adviceContext.registerBean(TelemetryValidationExceptionHandler.class,
                () -> new TelemetryValidationExceptionHandler(null, null));
        adviceContext.refresh();
        adviceBeans = ControllerAdviceBean.findAnnotatedBeans(adviceContext);
    }

    @AfterAll
    static void closeAdviceContext() {
        adviceContext.close();
    }

    @BeforeEach
    void setUp() {
        when(auditService.captureForCanonicalReading(any(), any()))
                .thenReturn(new TelemetrySubmissionAuditService.SubmissionAuditSnapshot(
                        "****0001", 7L, 1, LocalDate.of(2026, 3, 1)));

        ReadingRequestMapperRegistry registry = new ReadingRequestMapperRegistry(List.of(
                new CanonicalReadingRequestMapper(JsonMapper.builder().addModule(new JavaTimeModule()).build())));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ReadingWebhookController(imageWorkflowService, meterWorkflowService),
                        new SelectionWebhookController(selectionService),
                        new IssueReportWebhookController(meterWorkflowService),
                        new MeterChangeWebhookController(meterWorkflowService),
                        new ConversationWebhookController(messageService, welcomeMessageService),
                        new ReadingIngestController(imageWorkflowService, apiKeyService, bfmReadingService),
                        new MultiFormatReadingController(registry, apiKeyService, imageWorkflowService,
                                ReadingUrlTestValidation.validator()))
                .setValidator(ReadingUrlTestValidation.springValidator())
                .setControllerAdvice(
                        new WebhookValidationExceptionHandler(),
                        new TelemetryValidationExceptionHandler(auditService, eventPublisher))
                .build();
    }

    // ---- Binding: which advice Spring would select, for every controller in the service ----

    @Test
    @DisplayName("the two scoped advices are the only advices in the service")
    void theTwoScopedAdvicesAreTheOnlyOnes() {
        // A new advice — above all an unscoped, global one — changes the error contract of every
        // controller at once. Failing here forces a deliberate look at the bindings below.
        assertThat(ControllerRoutes.productionTypes(ControllerAdvice.class)).containsExactlyInAnyOrder(
                WebhookValidationExceptionHandler.class,
                TelemetryValidationExceptionHandler.class);
    }

    @Test
    @DisplayName("every @WebhookRoute controller is answered by the webhook advice alone")
    void everyWebhookControllerIsAnsweredByTheWebhookAdviceAlone() {
        Set<Class<?>> webhookControllers = ControllerRoutes.controllers().stream()
                .filter(controller -> controller.isAnnotationPresent(WebhookRoute.class))
                .collect(Collectors.toSet());

        assertThat(webhookControllers)
                .isNotEmpty()
                .allSatisfy(controller -> assertThat(advicesFor(controller))
                        .as("advices applying to %s", controller.getSimpleName())
                        .containsExactly(WebhookValidationExceptionHandler.class));
    }

    @Test
    @DisplayName("the canonical ingestion controller is answered by the ingest advice alone")
    void theCanonicalIngestControllerIsAnsweredByTheIngestAdviceAlone() {
        assertThat(advicesFor(ReadingIngestController.class))
                .containsExactly(TelemetryValidationExceptionHandler.class);
    }

    @Test
    @DisplayName("MultiFormatReadingController is answered by neither advice")
    void theMultiFormatControllerIsAnsweredByNeitherAdvice() {
        // It validates manually and handles its own malformed bodies, answering every failure in its
        // own envelope. Kept out so that a later change to the ingest advice cannot reach it.
        assertThat(advicesFor(MultiFormatReadingController.class)).isEmpty();
    }

    // ---- Webhook envelope, end to end ----

    @Test
    @DisplayName("a validation failure on /location is answered in the webhook envelope")
    void locationValidationFailureIsAnsweredInTheWebhookEnvelope() throws Exception {
        mockMvc.perform(post(TELEMETRY + "/location")
                        .contentType("application/json")
                        .content("{\"contact\":{\"phone\":\"91XXXXXXXXXX\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("must not be null")));

        verify(meterWorkflowService, never()).locationReadingMessage(any());
    }

    @Test
    @DisplayName("a validation failure on /issue-report/submit is answered in the webhook envelope")
    void issueReportValidationFailureIsAnsweredInTheWebhookEnvelope() throws Exception {
        mockMvc.perform(post(TELEMETRY + "/issue-report/submit")
                        .contentType("application/json")
                        .content("{\"contactId\":\"12345\",\"issueReason\":\"" + "a".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("issueReason must not exceed 255 characters"));

        verify(meterWorkflowService, never()).issueReportSubmitMessage(any());
    }

    // ---- Ingest envelope and reject capture, end to end ----

    @Test
    @DisplayName("a validation reject on POST /readings is answered in the readings envelope, audited and published")
    void readingsValidationRejectIsAnsweredAuditedAndPublished() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(TelemetryValidationExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(post(TELEMETRY + "/readings")
                            .header("X-Api-Key", "js_valid_key")
                            .contentType("application/json")
                            .content("{\"state_scheme_id\":\"S-1\",\"phone_number\":\"91XXXXXXXXXX\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.qualityStatus").value("REJECTED"))
                    .andExpect(jsonPath("$.data.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.data.message")
                            .value("Either readingUrl or confirmedReading must be provided"));
        } finally {
            logger.detachAppender(appender);
        }

        // The reported-scheme KPI: a validation reject still counts the scheme as having reported.
        verify(eventPublisher).publishSubmissionRejected(
                isNull(), eq("S-1"), isNull(), isNull(), startsWith("validation: "));
        verify(auditService).captureForCanonicalReading(any(), isNull());

        List<String> rejectLines = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("reading_validation_rejected "))
                .toList();
        assertThat(rejectLines)
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("phone=****0001", "schemeId=7")
                        .doesNotContain("91XXXXXXXXXX"));
    }

    @Test
    @DisplayName("an unreadable body on POST /readings is answered in the readings envelope")
    void readingsUnreadableBodyIsAnsweredInTheReadingsEnvelope() throws Exception {
        mockMvc.perform(post(TELEMETRY + "/readings")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content(UNREADABLE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("MALFORMED_REQUEST"));
    }

    /**
     * {@code MultiFormatReadingController} handles its own unreadable bodies, and a controller's own
     * {@code @ExceptionHandler} takes precedence over every advice — so this holds whatever the
     * binding. It pins the endpoint's contract; the binding itself is pinned by
     * {@link #theMultiFormatControllerIsAnsweredByNeitherAdvice()}.
     */
    @Test
    @DisplayName("an unreadable body on /readings/formats/{format} is answered in that endpoint's own envelope")
    void multiFormatUnreadableBodyIsAnsweredInItsOwnEnvelope() throws Exception {
        mockMvc.perform(post(TELEMETRY + "/readings/formats/canonical")
                        .header("X-Api-Key", "js_valid_key")
                        .contentType("application/json")
                        .content(UNREADABLE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").doesNotExist());

        verifyNoInteractions(eventPublisher, auditService);
    }

    private static Set<Class<?>> advicesFor(Class<?> controller) {
        return adviceBeans.stream()
                .filter(advice -> advice.isApplicableToBeanType(controller))
                .map(ControllerAdviceBean::getBeanType)
                .collect(Collectors.toSet());
    }
}
