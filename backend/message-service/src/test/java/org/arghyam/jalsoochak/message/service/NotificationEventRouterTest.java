package org.arghyam.jalsoochak.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.arghyam.jalsoochak.message.channel.GlificWhatsAppService;
import org.arghyam.jalsoochak.message.channel.SmsSender;
import org.arghyam.jalsoochak.message.channel.WhatsAppChannel;
import org.arghyam.jalsoochak.message.dto.DailyReportPriorityRow;
import org.arghyam.jalsoochak.message.dto.DailyReportSectionOfficerRow;
import org.arghyam.jalsoochak.message.exception.PermanentMailException;
import org.arghyam.jalsoochak.message.exception.TransientMailException;
import org.arghyam.jalsoochak.message.kafka.KafkaConfig;
import org.arghyam.jalsoochak.message.kafka.KafkaProducer;
import org.arghyam.jalsoochak.message.metrics.NotificationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.mockito.Spy;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link NotificationEventRouter}.
 *
 * <p>Verifies correct routing of NUDGE and ESCALATION events, skipping of
 * invalid payloads, and re-throwing of exceptions for Kafka retry/DLT.</p>
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventRouterTest {

    @Mock
    private WhatsAppChannel whatsAppChannel;

    @Mock
    private GlificWhatsAppService glificWhatsAppService;

    @Mock
    private KafkaProducer kafkaProducer;

    @Mock
    private EscalationPdfService escalationPdfService;

    @Mock
    private DailyReportPdfService dailyReportPdfService;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private MessageTemplateService messageTemplateService;

    @Mock
    private AccountEmailService accountEmailService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SmsSender smsSender;

    @Mock
    private PiiEncryptionService piiEncryptionService;

    /**
     * A real registry rather than a mock: {@code Counter.builder(...).register(registry)} goes
     * through the registry's own internals, which a bare Mockito mock cannot satisfy. {@code @Spy}
     * makes {@code @InjectMocks} pick up this instance.
     */
    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    /**
     * Real, not mocked, for the same reason as {@link #meterRegistry}: assertions below read the
     * counters back out, so the increments have to actually happen.
     */
    @Spy
    private NotificationMetrics notificationMetrics = new NotificationMetrics(meterRegistry);

    @InjectMocks
    private NotificationEventRouter router;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Inject real ObjectMapper and configure report dir via ReflectionTestUtils
        ReflectionTestUtils.setField(router, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(router, "reportDir", tempDir.toString() + "/");
        ReflectionTestUtils.setField(router, "baseUrl", "https://example.com");
        lenient().when(piiEncryptionService.hmac(anyString()))
                .thenAnswer(inv -> "hash_" + inv.getArgument(0, String.class));
        // publishJson now reports whether the broker acknowledged the record. Default to "yes" so
        // existing cases exercise the normal path; the drop cases override it explicitly.
        lenient().when(kafkaProducer.publishJson(anyString(), any())).thenReturn(true);
    }

    /** Reads a send-outcome counter back out of the registry. */
    private double sendCount(String channel, String eventType, String outcome) {
        var counter = meterRegistry.find(NotificationMetrics.SEND_COUNTER)
                .tag("channel", channel).tag("event_type", eventType).tag("outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * Helper method to stub jdbcTemplate.query for language-update user lookup (returns Long contact IDs).
     */
    @SuppressWarnings("unchecked")
    private void stubUserLookup(String tenantCode, String phone, List<Long> returnList) {
        when(jdbcTemplate.query(
                argThat(sql -> sql != null
                        && sql.contains("FROM tenant_" + tenantCode + ".user_table")
                        && sql.contains("WHERE phone_number = ?")
                        && !sql.contains("title")),
                any(RowMapper.class),
                eq(phone)))
                .thenReturn(returnList);
    }

    /**
     * Helper method to stub jdbcTemplate.query for welcome-flow contact info lookup (returns UserContactInfo).
     */
    @SuppressWarnings("unchecked")
    private void stubWelcomeLookup(String tenantCode, String phone,
                                   List<NotificationEventRouter.UserContactInfo> returnList) {
        when(jdbcTemplate.query(
                argThat(sql -> sql != null
                        && sql.contains("FROM tenant_" + tenantCode + ".user_table")
                        && sql.contains("WHERE phone_number = ?")
                        && sql.contains("title")),
                any(RowMapper.class),
                eq(phone)))
                .thenReturn(returnList);
    }

    // ──────────────────────────────── NUDGE ────────────────────────────────────

    @Test
    void route_sendsNudge_usingStoredContactId_whenPresent() {
        when(whatsAppChannel.sendNudgeViaFlow(anyLong(), anyString(), anyString())).thenReturn(true);

        router.route("""
                {"eventType":"NUDGE","recipientPhone":"919876543210",
                 "operatorName":"Ramesh","schemeId":"1","tenantId":1,"languageId":0,
                 "userId":10,"whatsappConnectionId":42,"tenantSchema":"tenant_mp"}
                """);

        verify(whatsAppChannel).sendNudgeViaFlow(eq(42L), eq("Ramesh"), anyString());
        verify(glificWhatsAppService, never()).optIn(anyString());
        verify(kafkaProducer, never()).publishJson(anyString(), any());
        verifyNoInteractions(escalationPdfService, minioStorageService, messageTemplateService);
    }

    @Test
    void route_fallsBackToOptIn_andPublishesEvent_whenNoStoredContactId() {
        when(glificWhatsAppService.optIn("919876543210")).thenReturn(99L);
        when(whatsAppChannel.sendNudgeViaFlow(anyLong(), anyString(), anyString())).thenReturn(true);

        router.route("""
                {"eventType":"NUDGE","recipientPhone":"919876543210",
                 "operatorName":"Ramesh","schemeId":"1","tenantId":1,"languageId":0,
                 "userId":10,"whatsappConnectionId":0,"tenantSchema":"tenant_mp"}
                """);

        verify(glificWhatsAppService).optIn("919876543210");
        verify(whatsAppChannel).sendNudgeViaFlow(eq(99L), eq("Ramesh"), anyString());
        verify(kafkaProducer).publishJson(eq("common-topic"), argThat(event -> {
            String s = event.toString();
            return s.contains("WHATSAPP_CONTACT_REGISTERED") && s.contains("99");
        }));
    }

    @Test
    void route_skipsNudge_whenPhoneIsBlank() {
        router.route("""
                {"eventType":"NUDGE","recipientPhone":"","operatorName":"Op","tenantId":1,"languageId":0}
                """);

        verifyNoInteractions(whatsAppChannel, glificWhatsAppService);
    }

    @Test
    void route_usesDefaultOperatorName_whenOperatorNameAbsent() {
        when(glificWhatsAppService.optIn(anyString())).thenReturn(55L);
        when(whatsAppChannel.sendNudgeViaFlow(anyLong(), anyString(), anyString())).thenReturn(true);

        router.route("""
                {"eventType":"NUDGE","recipientPhone":"911234567890","tenantId":1}
                """);

        verify(whatsAppChannel).sendNudgeViaFlow(anyLong(), eq("Operator"), anyString());
    }

    @Test
    void route_isCaseInsensitive_forNudgeEventType() {
        when(glificWhatsAppService.optIn(anyString())).thenReturn(55L);
        when(whatsAppChannel.sendNudgeViaFlow(anyLong(), anyString(), anyString())).thenReturn(true);

        router.route("""
                {"eventType":"nudge","recipientPhone":"919999999999","operatorName":"Op","tenantId":1}
                """);

        verify(whatsAppChannel).sendNudgeViaFlow(anyLong(), anyString(), anyString());
    }

    // ──────────────────────────── ESCALATION ───────────────────────────────────

    @Test
    void route_generatesAndSendsEscalation_usingStoredContactId_whenPresent() throws Exception {
        when(escalationPdfService.generate(anyList(), anyInt(), anyString(), anyString(), anyString())).thenReturn("report.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio.example.com/report.pdf");
        when(whatsAppChannel.sendDocument(anyLong(), anyString())).thenReturn(true);

        router.route("""
                {"eventType":"ESCALATION","officerPhone":"919876500000","officerName":"DO Singh",
                 "escalationLevel":2,"tenantId":1,"officerLanguageId":1,
                 "officerId":20,"officerWhatsappConnectionId":77,"tenantSchema":"tenant_mp",
                 "officerUserType":"JE","correlationId":"corr-stored",
                 "operators":[{"name":"Op A","phoneNumber":"911111111111","schemeName":"S1",
                               "schemeId":"1","soName":"SO X","consecutiveDaysMissed":8,
                               "lastRecordedBfmDate":"2024-01-01"}]}
                """);

        verify(escalationPdfService).generate(anyList(), eq(2), eq("DO Singh"), eq("JE"), eq("corr-stored"));
        verify(minioStorageService).upload(any(Path.class));
        verify(whatsAppChannel).sendDocument(eq(77L), eq("https://minio.example.com/report.pdf"));
        verify(glificWhatsAppService, never()).optIn(anyString());
        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void route_passesEmptyOfficerUserType_toGeneratePdf_whenFieldAbsentInPayload() throws Exception {
        when(escalationPdfService.generate(anyList(), anyInt(), anyString(), anyString(), anyString())).thenReturn("report.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio.example.com/report.pdf");
        when(whatsAppChannel.sendDocument(anyLong(), anyString())).thenReturn(true);

        router.route("""
                {"eventType":"ESCALATION","officerPhone":"919876500000","officerName":"DO Singh",
                 "escalationLevel":2,"tenantId":1,"officerLanguageId":1,
                 "officerId":20,"officerWhatsappConnectionId":77,"tenantSchema":"tenant_mp",
                 "correlationId":"corr-no-type",
                 "operators":[{"name":"Op A","phoneNumber":"911111111111","schemeName":"S1",
                               "schemeId":"1","soName":"SO X","consecutiveDaysMissed":8,
                               "lastRecordedBfmDate":"2024-01-01"}]}
                """);

        verify(escalationPdfService).generate(anyList(), eq(2), eq("DO Singh"), eq(""), eq("corr-no-type"));
    }

    @Test
    void route_passesEmptyCorrelationId_toGeneratePdf_whenFieldAbsentInPayload() throws Exception {
        when(escalationPdfService.generate(anyList(), anyInt(), anyString(), anyString(), eq(""))).thenReturn("report.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio.example.com/report.pdf");
        when(whatsAppChannel.sendDocument(anyLong(), anyString())).thenReturn(true);

        router.route("""
                {"eventType":"ESCALATION","officerPhone":"919876500000","officerName":"DO Singh",
                 "escalationLevel":2,"tenantId":1,"officerLanguageId":1,
                 "officerId":20,"officerWhatsappConnectionId":77,"tenantSchema":"tenant_mp",
                 "officerUserType":"JE",
                 "operators":[{"name":"Op A","phoneNumber":"911111111111","schemeName":"S1",
                               "schemeId":"1","soName":"SO X","consecutiveDaysMissed":8,
                               "lastRecordedBfmDate":"2024-01-01"}]}
                """);

        verify(escalationPdfService).generate(anyList(), eq(2), eq("DO Singh"), eq("JE"), eq(""));
    }

    @Test
    void route_fallsBackToOptIn_andPublishesEvent_forEscalation_whenNoStoredContactId() throws Exception {
        when(escalationPdfService.generate(anyList(), anyInt(), anyString(), anyString(), anyString())).thenReturn("r.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio.example.com/r.pdf");
        when(glificWhatsAppService.optIn("919876500000")).thenReturn(88L);
        when(whatsAppChannel.sendDocument(anyLong(), anyString())).thenReturn(true);

        router.route("""
                {"eventType":"ESCALATION","officerPhone":"919876500000","officerName":"DO Singh",
                 "escalationLevel":1,"tenantId":1,"officerLanguageId":1,
                 "officerId":20,"officerWhatsappConnectionId":0,"tenantSchema":"tenant_mp",
                 "operators":[{"name":"Op A","phoneNumber":"911111111111","schemeName":"S1",
                               "schemeId":"1","soName":"SO X","consecutiveDaysMissed":4,
                               "lastRecordedBfmDate":"2024-01-01"}]}
                """);

        verify(glificWhatsAppService).optIn("919876500000");
        verify(whatsAppChannel).sendDocument(eq(88L), anyString());
        verify(kafkaProducer).publishJson(eq("common-topic"), argThat(event -> {
            String s = event.toString();
            return s.contains("WHATSAPP_CONTACT_REGISTERED") && s.contains("88");
        }));
    }

    @Test
    void route_skipsEscalation_whenOfficerPhoneIsBlank() throws Exception {
        router.route("""
                {"eventType":"ESCALATION","officerPhone":"","officerName":"DO","escalationLevel":1,
                 "tenantId":1,"officerLanguageId":0,
                 "operators":[{"name":"Op","phoneNumber":"911111111111","schemeName":"S","schemeId":"1",
                               "soName":"SO","consecutiveDaysMissed":5,"lastRecordedBfmDate":"2024-01-01"}]}
                """);

        verifyNoInteractions(escalationPdfService, minioStorageService, whatsAppChannel);
    }

    @Test
    void route_skipsEscalation_whenOperatorsListIsEmpty() {
        router.route("""
                {"eventType":"ESCALATION","officerPhone":"919876500001","officerName":"DO",
                 "escalationLevel":1,"tenantId":1,"officerLanguageId":0,"operators":[]}
                """);

        verifyNoInteractions(escalationPdfService, minioStorageService, whatsAppChannel);
    }

    @Test
    void route_isCaseInsensitive_forEscalationEventType() throws Exception {
        when(escalationPdfService.generate(anyList(), anyInt(), anyString(), anyString(), eq("corr-case"))).thenReturn("r.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio.example.com/r.pdf");
        when(glificWhatsAppService.optIn(anyString())).thenReturn(11L);
        when(whatsAppChannel.sendDocument(anyLong(), anyString())).thenReturn(true);

        router.route("""
                {"eventType":"escalation","officerPhone":"919876500002","officerName":"DO",
                 "escalationLevel":1,"tenantId":1,"officerLanguageId":0,"correlationId":"corr-case",
                 "operators":[{"name":"Op","phoneNumber":"911111111112","schemeName":"S","schemeId":"1",
                               "soName":"SO","consecutiveDaysMissed":4,"lastRecordedBfmDate":"2024-01-01"}]}
                """);

        verify(whatsAppChannel).sendDocument(anyLong(), anyString());
    }

    // ───────────────────────────── error handling ──────────────────────────────

    @Test
    void route_ignoresUnknownEventType_silently() {
        router.route("""
                {"eventType":"SOME_UNKNOWN_TYPE","data":"irrelevant"}
                """);

        verifyNoInteractions(whatsAppChannel, escalationPdfService, minioStorageService);
    }

    @Test
    void route_rethrowsException_forKafkaRetry_whenNudgeFails() {
        when(glificWhatsAppService.optIn(anyString())).thenReturn(55L);
        when(whatsAppChannel.sendNudgeViaFlow(anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Glific unreachable"));

        assertThatThrownBy(() -> router.route("""
                {"eventType":"NUDGE","recipientPhone":"919000000001","operatorName":"Op","tenantId":1}
                """))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");
    }

    @Test
    void route_rethrowsException_forKafkaRetry_whenPdfGenerationFails() throws Exception {
        when(escalationPdfService.generate(anyList(), anyInt(), anyString(), anyString(), eq("corr-pdf-fail")))
                .thenThrow(new RuntimeException("PDF write failed"));

        assertThatThrownBy(() -> router.route("""
                {"eventType":"ESCALATION","officerPhone":"919876500003","officerName":"DO",
                 "escalationLevel":1,"tenantId":1,"officerLanguageId":0,"correlationId":"corr-pdf-fail",
                 "operators":[{"name":"Op","phoneNumber":"911111111113","schemeName":"S","schemeId":"1",
                               "soName":"SO","consecutiveDaysMissed":4,"lastRecordedBfmDate":"2024-01-01"}]}
                """))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");
    }

    @Test
    void route_rethrowsException_forKafkaRetry_whenMinioUploadFails() throws Exception {
        when(escalationPdfService.generate(anyList(), anyInt(), anyString(), anyString(), eq("corr-minio-fail"))).thenReturn("r.pdf");
        when(minioStorageService.upload(any(Path.class))).thenThrow(new Exception("MinIO error"));

        assertThatThrownBy(() -> router.route("""
                {"eventType":"ESCALATION","officerPhone":"919876500004","officerName":"DO",
                 "escalationLevel":1,"tenantId":1,"officerLanguageId":0,"correlationId":"corr-minio-fail",
                 "operators":[{"name":"Op","phoneNumber":"911111111114","schemeName":"S","schemeId":"1",
                               "soName":"SO","consecutiveDaysMissed":4,"lastRecordedBfmDate":"2024-01-01"}]}
                """))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");
    }

    // ───────────────────────── STAFF_SYNC_COMPLETED ────────────────────────────

    @Test
    void route_onboardsAllOperators_forValidStaffSyncEvent_andPublishesContactRegisteredEvents() {
        when(whatsAppChannel.onboardOperator("919876543210", 2)).thenReturn(42L);
        when(whatsAppChannel.onboardOperator("919123456789", 2)).thenReturn(43L);

        router.route("""
                {"eventType":"STAFF_SYNC_COMPLETED","tenantCode":"MP","tenantId":1,
                 "glificLanguageId":"2","tenantSchema":"tenant_mp",
                 "pumpOperators":[{"userId":10,"phone":"919876543210"},{"userId":11,"phone":"919123456789"}]}
                """);

        verify(whatsAppChannel).onboardOperator("919876543210", 2);
        verify(whatsAppChannel).onboardOperator("919123456789", 2);
        verify(kafkaProducer, times(2)).publishJson(eq("common-topic"), any());
        verifyNoInteractions(escalationPdfService, minioStorageService, messageTemplateService);
    }

    @Test
    void route_skipsStaffSync_whenOperatorsArrayIsEmpty() {
        router.route("""
                {"eventType":"STAFF_SYNC_COMPLETED","tenantCode":"MP","tenantId":1,
                 "glificLanguageId":"2","tenantSchema":"tenant_mp","pumpOperators":[]}
                """);

        verifyNoInteractions(whatsAppChannel);
    }

    @Test
    void route_skipsStaffSync_whenGlificLanguageIdIsZero() {
        router.route("""
                {"eventType":"STAFF_SYNC_COMPLETED","tenantCode":"MP","tenantId":1,
                 "glificLanguageId":"0","tenantSchema":"tenant_mp",
                 "pumpOperators":[{"userId":10,"phone":"919876543210"}]}
                """);

        verifyNoInteractions(whatsAppChannel);
    }

    @Test
    void route_skipsStaffSync_whenGlificLanguageIdIsMissing() {
        router.route("""
                {"eventType":"STAFF_SYNC_COMPLETED","tenantCode":"MP","tenantId":1,
                 "tenantSchema":"tenant_mp","pumpOperators":[{"userId":10,"phone":"919876543210"}]}
                """);

        verifyNoInteractions(whatsAppChannel);
    }

    @Test
    void route_rethrowsException_whenAllStaffSyncOnboardingsFail() {
        doThrow(new RuntimeException("Glific error"))
                .when(whatsAppChannel).onboardOperator(anyString(), anyInt());

        assertThatThrownBy(() -> router.route("""
                {"eventType":"STAFF_SYNC_COMPLETED","tenantCode":"MP","tenantId":1,
                 "glificLanguageId":"2","tenantSchema":"tenant_mp",
                 "pumpOperators":[{"userId":10,"phone":"919876543210"},{"userId":11,"phone":"919123456789"}]}
                """))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");
    }

    @Test
    void route_rethrowsException_whenPartialStaffSyncOnboardingFails() {
        doThrow(new RuntimeException("Glific error"))
                .when(whatsAppChannel).onboardOperator(eq("919876543210"), anyInt());
        when(whatsAppChannel.onboardOperator(eq("919123456789"), anyInt())).thenReturn(43L);

        assertThatThrownBy(() -> router.route("""
                {"eventType":"STAFF_SYNC_COMPLETED","tenantCode":"MP","tenantId":1,
                 "glificLanguageId":"2","tenantSchema":"tenant_mp",
                 "pumpOperators":[{"userId":10,"phone":"919876543210"},{"userId":11,"phone":"919123456789"}]}
                """))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");

        verify(whatsAppChannel).onboardOperator("919876543210", 2);
        verify(whatsAppChannel).onboardOperator("919123456789", 2);
    }

    @Test
    void route_rethrowsException_onMalformedJson() {
        assertThatThrownBy(() -> router.route("{not valid json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");
    }

    // ───────────────────────── SEND_INVITE_EMAIL ───────────────────────────────

    @Test
    void route_dispatchesInviteEmail_forValidEvent() {
        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"op@tenant.in",
                 "name":"Mohan","role":"NEW_ROLE",
                 "inviteLink":"https://app.jalsoochak.in/activate?token=abc","expiryHours":24}
                """);

        verify(accountEmailService).sendInviteEmail(
                "op@tenant.in", "Mohan", "NEW_ROLE",
                "https://app.jalsoochak.in/activate?token=abc", 24);
        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void route_isCaseInsensitive_forInviteEmailEventType() {
        router.route("""
                {"eventType":"send_invite_email","to":"op@tenant.in","name":"Dev",
                 "role":"FIELD_OFFICER","inviteLink":"https://link","expiryHours":12}
                """);

        verify(accountEmailService).sendInviteEmail(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void route_routesToDlt_whenInviteEmailMissingToField() {
        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","name":"Dev",
                 "inviteLink":"https://link","expiryHours":24}
                """);

        verify(accountEmailService, never()).sendInviteEmail(anyString(), anyString(), anyString(), anyString(), anyInt());
        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(payload -> {
            String s = payload.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("missing_to");
        }));
    }

    @Test
    void route_routesToDlt_whenInviteEmailMissingInviteLink() {
        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"admin@state.gov","name":"Dev",
                 "role":"STATE_ADMIN","expiryHours":24}
                """);

        verify(accountEmailService, never()).sendInviteEmail(anyString(), anyString(), anyString(), anyString(), anyInt());
        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(payload -> {
            String s = payload.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("missing_invite_link");
        }));
    }

    @Test
    void route_routesToDlt_whenInviteEmailSmtpFails() {
        doThrow(new RuntimeException("SMTP down"))
                .when(accountEmailService).sendInviteEmail(anyString(), anyString(), anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"op@tenant.in","name":"Dev",
                 "role":"NEW_ROLE","inviteLink":"https://link","expiryHours":24}
                """);

        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(payload -> {
            String s = payload.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("email_delivery_error");
        }));
    }

    // ─────────── Transient vs permanent: which failures earn a Kafka retry ───────────

    @Test
    void route_rethrowsAndSkipsDlt_whenInviteEmailFailsTransiently() {
        // 429/5xx/connection failures delivered nothing, so the container should replay the event
        // rather than bury it on a topic nobody consumes.
        doThrow(new TransientMailException("SendGrid returned HTTP 503", null))
                .when(accountEmailService).sendInviteEmail(anyString(), anyString(), anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"op@tenant.in","name":"Dev",
                 "role":"NEW_ROLE","inviteLink":"https://link","expiryHours":24}
                """))
                .isInstanceOf(RuntimeException.class);

        verify(kafkaProducer, never()).publishJson(eq("account-email-dlt"), any());
    }

    /**
     * Nothing consumes account-email-dlt, so this counter is the only signal that a user never got
     * their invite. Without it, a dead-lettered email and a delivered one are indistinguishable.
     */
    @Test
    void route_countsADeadLetteredEmail_soTheSilentFailureIsAlertable() {
        doThrow(new PermanentMailException("SendGrid returned HTTP 400", null))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"user@example.com",
                 "resetLink":"https://reset?token=abc","expiryMinutes":30}
                """);

        var counter = ((SimpleMeterRegistry) meterRegistry)
                .find(KafkaConfig.DEADLETTER_COUNTER)
                .tag("outcome", "deadlettered")
                .tag("event_type", "SEND_PASSWORD_RESET_EMAIL")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void route_countsADroppedEmail_whenEvenTheDeadLetterPublishFails() {
        doThrow(new PermanentMailException("SendGrid returned HTTP 400", null))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());
        doThrow(new RuntimeException("broker unavailable"))
                .when(kafkaProducer).publishJson(eq("account-email-dlt"), any());

        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"user@example.com",
                 "resetLink":"https://reset?token=abc","expiryMinutes":30}
                """);

        // Lost outright rather than parked — the outcome that should page someone.
        var counter = ((SimpleMeterRegistry) meterRegistry)
                .find(KafkaConfig.DEADLETTER_COUNTER).tag("outcome", "dropped").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void route_routesToDlt_whenInviteEmailFailsPermanently() {
        doThrow(new PermanentMailException("SendGrid returned HTTP 400", null))
                .when(accountEmailService).sendInviteEmail(anyString(), anyString(), anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"op@tenant.in","name":"Dev",
                 "role":"NEW_ROLE","inviteLink":"https://link","expiryHours":24}
                """);

        verify(kafkaProducer).publishJson(eq("account-email-dlt"), any());
    }

    @Test
    void route_rethrowsAndSkipsDlt_whenPasswordResetFailsTransiently() {
        doThrow(new TransientMailException("SendGrid returned HTTP 429", null))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"user@example.com",
                 "resetLink":"https://reset?token=abc","expiryMinutes":30}
                """))
                .isInstanceOf(RuntimeException.class);

        verify(kafkaProducer, never()).publishJson(eq("account-email-dlt"), any());
    }

    @Test
    void route_rethrowsAndSkipsDlt_whenReinviteFailsTransiently() {
        doThrow(new TransientMailException("connection reset", null))
                .when(accountEmailService).sendReinviteEmail(anyString(), anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> router.route("""
                {"eventType":"SEND_REINVITE_EMAIL","to":"op@tenant.in","name":"Sunita",
                 "inviteLink":"https://link","expiryHours":72}
                """))
                .isInstanceOf(RuntimeException.class);

        verify(kafkaProducer, never()).publishJson(eq("account-email-dlt"), any());
    }

    @Test
    void route_routesToDlt_whenEmailFailsWithAnUnclassifiedError() {
        // An adapter that throws neither type must still be treated as permanent: a duplicate
        // email is worse than a delayed one, so the safe default is "do not replay".
        doThrow(new IllegalStateException("something odd"))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"user@example.com",
                 "resetLink":"https://reset?token=abc","expiryMinutes":30}
                """);

        verify(kafkaProducer).publishJson(eq("account-email-dlt"), any());
    }

    // ──────────────────────── SEND_REINVITE_EMAIL ──────────────────────────────

    @Test
    void route_dispatchesReinviteEmail_forValidEvent() {
        router.route("""
                {"eventType":"SEND_REINVITE_EMAIL","to":"op@tenant.in",
                 "name":"Sunita","inviteLink":"https://app.jalsoochak.in/activate?token=re","expiryHours":72}
                """);

        verify(accountEmailService).sendReinviteEmail(
                "op@tenant.in", "Sunita",
                "https://app.jalsoochak.in/activate?token=re", 72);
        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void route_routesToDlt_whenReinviteEmailMissingToField() {
        router.route("""
                {"eventType":"SEND_REINVITE_EMAIL","name":"Sunita",
                 "inviteLink":"https://link","expiryHours":72}
                """);

        verify(accountEmailService, never()).sendReinviteEmail(anyString(), anyString(), anyString(), anyInt());
        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(payload -> {
            String s = payload.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("missing_to");
        }));
    }

    @Test
    void route_routesToDlt_whenReinviteEmailMissingInviteLink() {
        router.route("""
                {"eventType":"SEND_REINVITE_EMAIL","to":"op@tenant.in",
                 "name":"Sunita","expiryHours":72}
                """);

        verify(accountEmailService, never()).sendReinviteEmail(anyString(), anyString(), anyString(), anyInt());
        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(payload -> {
            String s = payload.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("missing_invite_link");
        }));
    }

    @Test
    void route_routesToDlt_whenReinviteEmailSmtpFails() {
        doThrow(new RuntimeException("SMTP down"))
                .when(accountEmailService).sendReinviteEmail(anyString(), anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_REINVITE_EMAIL","to":"op@tenant.in","name":"Sunita",
                 "inviteLink":"https://link","expiryHours":72}
                """);

        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(payload -> {
            String s = payload.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("email_delivery_error");
        }));
    }

    // ─────────────────────── SEND_PASSWORD_RESET_EMAIL ─────────────────────────

    @Test
    void route_dispatchesPasswordResetEmail_forValidEvent() {
        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"user@example.com",
                 "resetLink":"https://app.jalsoochak.in/reset?token=r1","expiryMinutes":30}
                """);

        verify(accountEmailService).sendPasswordResetEmail(
                "user@example.com", "https://app.jalsoochak.in/reset?token=r1", 30);
        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void route_isCaseInsensitive_forPasswordResetEmailEventType() {
        router.route("""
                {"eventType":"send_password_reset_email","to":"user@example.com",
                 "resetLink":"https://link","expiryMinutes":15}
                """);

        verify(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());
    }

    @Test
    void route_routesToDlt_whenPasswordResetEmailMissingToField() {
        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL",
                 "resetLink":"https://link","expiryMinutes":30}
                """);

        verify(accountEmailService, never()).sendPasswordResetEmail(anyString(), anyString(), anyInt());
        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(payload -> {
            String s = payload.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("missing_to");
        }));
    }

    @Test
    void route_routesToDlt_whenPasswordResetEmailMissingResetLink() {
        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"user@example.com","expiryMinutes":30}
                """);

        verify(accountEmailService, never()).sendPasswordResetEmail(anyString(), anyString(), anyInt());
        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(payload -> {
            String s = payload.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("missing_reset_link");
        }));
    }

    @Test
    void route_routesToDlt_whenPasswordResetEmailSmtpFails() {
        doThrow(new RuntimeException("SMTP down"))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"user@example.com",
                 "resetLink":"https://link","expiryMinutes":30}
                """);

        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(payload -> {
            String s = payload.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("email_delivery_error");
        }));
    }

    @Test
    void route_doesNotThrow_whenDltPublishFails() {
        // SMTP fails triggering DLT publish, but DLT publish itself also throws.
        // The handler must swallow the DLT failure and complete normally (no rethrow → no Kafka retry).
        doThrow(new RuntimeException("SMTP down"))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());
        doThrow(new RuntimeException("Kafka unavailable"))
                .when(kafkaProducer).publishJson(anyString(), any());

        // Should not throw
        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"user@example.com",
                 "resetLink":"https://link","expiryMinutes":30}
                """);

        verify(kafkaProducer).publishJson(eq("account-email-dlt"), any());
    }

    // ──────────────────────────────── SEND_LOGIN_OTP ───────────────────────────

    @Test
    void route_sendsLoginOtp_usingStoredGlificId() {
        when(whatsAppChannel.sendLoginOtp(42L, "654321")).thenReturn(true);

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO Singh",
                 "OTP":"654321","deliveryChannel":"WHATSAPP","glific_id":42}
                """);

        verify(whatsAppChannel).sendLoginOtp(42L, "654321");
        verify(glificWhatsAppService, never()).optIn(anyString());
    }

    @Test
    void route_sendsLoginOtp_usingOptIn_whenGlificIdAbsent() {
        when(glificWhatsAppService.optIn("919876500010")).thenReturn(77L);
        when(whatsAppChannel.sendLoginOtp(77L, "654321")).thenReturn(true);

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO Singh",
                 "OTP":"654321","deliveryChannel":"WHATSAPP","glific_id":"","officerPhoneNumber":"919876500010"}
                """);

        verify(glificWhatsAppService).optIn("919876500010");
        verify(whatsAppChannel).sendLoginOtp(77L, "654321");
    }

    @Test
    void route_skipsLoginOtp_whenOtpIsBlank() {
        router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO","OTP":"","glific_id":"42"}
                """);

        verifyNoInteractions(whatsAppChannel, glificWhatsAppService);
    }

    @Test
    void route_skipsLoginOtp_whenNeitherGlificIdNorPhoneProvided() {
        router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO","OTP":"999999",
                 "glific_id":"","officerPhoneNumber":""}
                """);

        verifyNoInteractions(whatsAppChannel, glificWhatsAppService);
    }

    @Test
    void route_skipsLoginOtp_whenGlificIdIsInvalidNumber() {
        router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO","OTP":"111111",
                 "glific_id":"not-a-number"}
                """);

        verifyNoInteractions(whatsAppChannel, glificWhatsAppService);
    }

    /**
     * A failed WhatsApp OTP is terminal, exactly like the SMS branch. Throwing here would buy the
     * common-topic retry ladder — SEND_LOGIN_OTP is still accepted there during the migration — whose
     * last rung lands past the 60s resend cooldown, after {@code requestOtp} has revoked this code.
     * The user would be handed a dead OTP and lose one of three verification attempts, while the
     * partition stayed occupied and delayed other logins.
     */
    @Test
    void route_doesNotRethrow_whenWhatsAppLoginOtpDeliveryFails() {
        when(glificWhatsAppService.optIn(anyString())).thenReturn(55L);
        when(whatsAppChannel.sendLoginOtp(anyLong(), anyString())).thenReturn(false);

        assertThatCode(() -> router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO","OTP":"222222",
                 "deliveryChannel":"WHATSAPP","glific_id":"","officerPhoneNumber":"919000000002"}
                """)).doesNotThrowAnyException();

        verify(whatsAppChannel).sendLoginOtp(55L, "222222");
    }

    @Test
    void route_doesNotRethrow_whenTheWhatsAppOptInForALoginOtpThrows() {
        // Glific being down must not park an OTP on the retry ladder either.
        when(glificWhatsAppService.optIn(anyString()))
                .thenThrow(new RuntimeException("Glific unavailable"));

        assertThatCode(() -> router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO","OTP":"222333",
                 "deliveryChannel":"WHATSAPP","glific_id":"","officerPhoneNumber":"919000000003"}
                """)).doesNotThrowAnyException();

        verifyNoInteractions(whatsAppChannel);
    }

    // ──────────────────────── SEND_WELCOME_MESSAGE ─────────────────────────────

    @Test
    void route_sendsWelcomeMessage_whenContactIdFound() {
        stubWelcomeLookup("mp", "919111111111",
                List.of(new NotificationEventRouter.UserContactInfo(88L, "Ramesh Kumar")));
        when(messageTemplateService.findStateName(anyInt())).thenReturn("Madhya Pradesh");

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE","tenantCode":"mp",
                 "pumpOperatorPhones":["919111111111"]}
                """);

        verify(glificWhatsAppService).startWelcomeFlow(88L, "Ramesh Kumar", "Madhya Pradesh");
        verify(kafkaProducer, never()).publishJson(eq("welcome-message-dlt"), any());
    }

    @Test
    void route_routesToDlt_whenNoContactIdFound() {
        stubWelcomeLookup("mp", "919222222222", List.of());
        when(messageTemplateService.findStateName(anyInt())).thenReturn("");

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE","tenantCode":"mp",
                 "pumpOperatorPhones":["919222222222"]}
                """);

        verify(glificWhatsAppService, never()).startWelcomeFlow(anyLong(), anyString(), anyString());
        verify(kafkaProducer).publishJson(eq("welcome-message-dlt"), any());
    }

    /**
     * welcome-message-dlt is as unconsumed as account-email-dlt, so it needs the same counter or an
     * operator who never got their welcome message leaves no trace outside the log file.
     */
    @Test
    void route_countsADeadLetteredWelcomeMessage_soTheSilentFailureIsAlertable() {
        stubWelcomeLookup("mp", "919222222222", List.of());
        when(messageTemplateService.findStateName(anyInt())).thenReturn("");

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE","tenantCode":"mp",
                 "pumpOperatorPhones":["919222222222"]}
                """);

        var counter = ((SimpleMeterRegistry) meterRegistry)
                .find(KafkaConfig.DEADLETTER_COUNTER)
                .tag("outcome", "deadlettered")
                .tag("topic", "welcome-message-dlt")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void route_countsADroppedWelcomeMessage_andDoesNotRethrow_whenTheDeadLetterPublishFails() {
        stubWelcomeLookup("mp", "919222222222", List.of());
        when(messageTemplateService.findStateName(anyInt())).thenReturn("");
        doThrow(new RuntimeException("broker unavailable"))
                .when(kafkaProducer).publishJson(eq("welcome-message-dlt"), any());

        // Rethrowing would send the handler back round the retry ladder and re-send the welcome
        // message to every operator that already received one.
        assertThatCode(() -> router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE","tenantCode":"mp",
                 "pumpOperatorPhones":["919222222222"]}
                """)).doesNotThrowAnyException();

        var counter = ((SimpleMeterRegistry) meterRegistry)
                .find(KafkaConfig.DEADLETTER_COUNTER)
                .tag("outcome", "dropped")
                .tag("topic", "welcome-message-dlt")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    /**
     * Prometheus requires every sample of a metric to carry the same label keys, and Micrometer
     * enforces that at registration time — the second registration of {@code jalsoochak.deadletter}
     * with a different tag set throws. Both paths share the counter, so if their tag keys ever drift
     * apart one of them dies at runtime and takes the dead-letter alerts with it.
     *
     * <p>{@link SimpleMeterRegistry} does not enforce this, which is exactly why this one test uses
     * a real {@link PrometheusMeterRegistry}. Its counterpart on the container side is
     * {@code KafkaConfigTest#deadLetterCounter_usesTheSameTagKeysAsTheRouter}; the two assert the
     * same key set from opposite ends because the recoverer is not visible from this package.
     */
    @Test
    void deadLetterCounter_registersTheAgreedTagKeys() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ReflectionTestUtils.setField(router, "meterRegistry", prometheus);

        doThrow(new PermanentMailException("SendGrid returned HTTP 400", null))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());

        assertThatCode(() -> router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"user@example.com",
                 "resetLink":"https://reset?token=abc","expiryMinutes":30}
                """)).doesNotThrowAnyException();

        assertThat(prometheus.find(KafkaConfig.DEADLETTER_COUNTER).counters())
                .isNotEmpty()
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .extracting(Tag::getKey)
                        .containsExactlyInAnyOrder("outcome", "topic", "event_type"));
    }

    @Test
    void route_skipsWelcomeMessage_whenTenantCodeIsBlank() {
        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE","tenantCode":"",
                 "pumpOperatorPhones":["919333333333"]}
                """);

        verifyNoInteractions(glificWhatsAppService);
    }

    @Test
    void route_skipsWelcomeMessage_whenPhonesListIsEmpty() {
        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE","tenantCode":"mp",
                 "pumpOperatorPhones":[]}
                """);

        verifyNoInteractions(glificWhatsAppService);
    }

    // ──────────────────────── UPDATE_USER_LANGUAGE ─────────────────────────────

    @Test
    void route_updatesLanguage_whenContactIdFound() {
        stubUserLookup("mp", "919444444444", List.of(99L));

        router.route("""
                {"eventType":"UPDATE_USER_LANGUAGE","tenantCode":"mp",
                 "glificLanguageId":3,
                 "pumpOperatorPhones":["919444444444"]}
                """);

        verify(glificWhatsAppService).updateContactLanguage(99L, 3);
    }

    @Test
    void route_rethrowsException_whenUpdateLanguageFails_forPhoneNotFound() {
        stubUserLookup("mp", "919555555555", List.of());

        assertThatThrownBy(() -> router.route("""
                {"eventType":"UPDATE_USER_LANGUAGE","tenantCode":"mp",
                 "glificLanguageId":2,
                 "pumpOperatorPhones":["919555555555"]}
                """))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");
    }

    @Test
    void route_skipsUpdateLanguage_whenTenantCodeIsInvalid() {
        router.route("""
                {"eventType":"UPDATE_USER_LANGUAGE","tenantCode":"INVALID CODE!",
                 "glificLanguageId":1,
                 "pumpOperatorPhones":["919666666666"]}
                """);

        verifyNoInteractions(glificWhatsAppService, jdbcTemplate);
    }

    @Test
    void route_skipsUpdateLanguage_whenGlificLanguageIdIsZero() {
        router.route("""
                {"eventType":"UPDATE_USER_LANGUAGE","tenantCode":"mp",
                 "glificLanguageId":0,
                 "pumpOperatorPhones":["919777777777"]}
                """);

        verifyNoInteractions(glificWhatsAppService, jdbcTemplate);
    }

    // ──────────────────── SEND_WELCOME_MESSAGE_ADMIN ───────────────────────────

    @Test
    void route_skipsWelcomeMessageAdmin_whenTenantCodeIsBlank() {
        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE_ADMIN","tenantCode":"",
                 "pumpOperatorPhones":["919100000001"]}
                """);

        verifyNoInteractions(glificWhatsAppService);
    }

    @Test
    void route_skipsWelcomeMessageAdmin_whenTenantCodeContainsSpecialChars() {
        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE_ADMIN","tenantCode":"invalid code!",
                 "pumpOperatorPhones":["919100000002"]}
                """);

        verifyNoInteractions(glificWhatsAppService);
    }

    @Test
    void route_skipsWelcomeMessageAdmin_whenPhonesListIsEmpty() {
        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE_ADMIN","tenantCode":"mp",
                 "pumpOperatorPhones":[]}
                """);

        verifyNoInteractions(glificWhatsAppService);
    }

    @Test
    void route_sendsWelcomeAdmin_whenContactIdFoundInDb() {
        stubWelcomeLookup("mp", "919100000003",
                List.of(new NotificationEventRouter.UserContactInfo(55L, "Operator A")));
        when(messageTemplateService.findStateName(anyInt())).thenReturn("Madhya Pradesh");

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE_ADMIN","tenantCode":"mp","tenantId":1,
                 "pumpOperatorPhones":["919100000003"]}
                """);

        verify(glificWhatsAppService).startWelcomeFlow(55L, "Operator A", "Madhya Pradesh");
        verify(kafkaProducer, never()).publishJson(eq("welcome-message-dlt"), any());
    }

    @Test
    void route_optInsAndSendsWelcomeAdmin_whenContactIdNotFoundInDb() {
        stubWelcomeLookup("mp", "919100000004", List.of());
        when(glificWhatsAppService.optIn("919100000004")).thenReturn(66L);
        when(messageTemplateService.findStateName(anyInt())).thenReturn("MP");

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE_ADMIN","tenantCode":"mp","tenantId":1,
                 "pumpOperatorPhones":["919100000004"]}
                """);

        verify(glificWhatsAppService).optIn("919100000004");
        verify(glificWhatsAppService).startWelcomeFlow(66L, null, "MP");
        verify(kafkaProducer, never()).publishJson(eq("welcome-message-dlt"), any());
    }

    @Test
    void route_routesToDlt_whenOptInFails_forWelcomeAdmin() {
        stubWelcomeLookup("mp", "919100000005", List.of());
        when(glificWhatsAppService.optIn(anyString())).thenReturn(0L);
        when(messageTemplateService.findStateName(anyInt())).thenReturn("");

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE_ADMIN","tenantCode":"mp","tenantId":1,
                 "pumpOperatorPhones":["919100000005"]}
                """);

        verify(kafkaProducer).publishJson(eq("welcome-message-dlt"),
                argThat(p -> p.toString().contains("optin_failed")));
    }

    @Test
    void route_routesToDlt_whenPhoneIsBlankAfterNormalization_forWelcomeAdmin() {
        when(messageTemplateService.findStateName(anyInt())).thenReturn("");

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE_ADMIN","tenantCode":"mp","tenantId":1,
                 "pumpOperatorPhones":[""]}
                """);

        verify(kafkaProducer).publishJson(eq("welcome-message-dlt"),
                argThat(p -> p.toString().contains("blank_phone")));
        verifyNoInteractions(glificWhatsAppService);
    }

    @Test
    void route_normalizesPhone_from10DigitsTo91Prefix_forWelcomeAdmin() {
        // A 10-digit phone ("9876543210") must be prefixed to "919876543210" before the optIn call.
        // We skip DB lookups returning empty (no contactId) so that optIn is reached with the
        // normalized number, which lets us assert the prefix was applied without needing two
        // jdbcTemplate stubs on the same method signature.
        when(messageTemplateService.findStateName(anyInt())).thenReturn("");
        when(glificWhatsAppService.optIn("919876543210")).thenReturn(77L);

        // Stub the jdbcTemplate lookup for the raw AND normalized phone.
        // Use lenient() because only one will actually be called depending on equality check.
        org.mockito.Mockito.lenient()
                .when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("user_table")),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(List.of());

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE_ADMIN","tenantCode":"mp","tenantId":1,
                 "pumpOperatorPhones":["9876543210"]}
                """);

        verify(glificWhatsAppService).optIn("919876543210");
    }

    // ───────────────────── send-outcome metrics ────────────────────────
    //
    // The OTP channels are why this counter exists. otp-topic runs FixedBackOff(0, 0) and both OTP
    // branches swallow their own failures on purpose — retrying an OTP hands the user a code that
    // requestOtp has already revoked — so an OTP failure reaches no recoverer, increments no
    // dead-letter counter, and fires none of the dead-letter alerts. Before this counter, an
    // SMSCountry outage was visible only as a log line nobody was watching.

    @Test
    void route_countsAFailedSmsOtp_soAProviderOutageIsVisibleToMonitoring() {
        when(smsSender.sendOtp(anyString(), anyString(), anyInt()))
                .thenReturn(Mono.error(new RuntimeException("SMSCountry OTP send failed (server error)")));

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500030","expiryMinutes":5}
                """);

        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.FAILED)).isEqualTo(1.0);
        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.SENT)).isZero();
    }

    /** A 4xx from the provider: terminal, and distinct from an outage. */
    @Test
    void route_countsARejectedSmsOtp_separatelyFromAFailure() {
        when(smsSender.sendOtp(anyString(), anyString(), anyInt())).thenReturn(Mono.just(false));

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500031","expiryMinutes":5}
                """);

        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.REJECTED)).isEqualTo(1.0);
        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.FAILED)).isZero();
    }

    @Test
    void route_countsASentSmsOtp() {
        when(smsSender.sendOtp(anyString(), anyString(), anyInt())).thenReturn(Mono.just(true));

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500032","expiryMinutes":5}
                """);

        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.SENT)).isEqualTo(1.0);
    }

    @Test
    void route_countsAFailedWhatsAppOtp() {
        when(whatsAppChannel.sendLoginOtp(anyLong(), anyString())).thenReturn(false);

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"WHATSAPP","glific_id":4242}
                """);

        assertThat(sendCount("WHATSAPP", "SEND_LOGIN_OTP", NotificationMetrics.FAILED)).isEqualTo(1.0);
    }

    /** A thrown Glific error is the same outcome as a false return — the user got nothing. */
    @Test
    void route_countsAThrownWhatsAppOtpFailure() {
        when(whatsAppChannel.sendLoginOtp(anyLong(), anyString()))
                .thenThrow(new RuntimeException("Glific auth token expired"));

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"WHATSAPP","glific_id":4242}
                """);

        assertThat(sendCount("WHATSAPP", "SEND_LOGIN_OTP", NotificationMetrics.FAILED)).isEqualTo(1.0);
    }

    /** An unusable event is a producer bug, not a delivery failure, and alerts separately. */
    @Test
    void route_countsASkippedOtp_whenTheEventIsUnusable() {
        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"SMS","officerPhoneNumber":""}
                """);

        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.SKIPPED)).isEqualTo(1.0);
        verifyNoInteractions(smsSender);
    }

    @Test
    void route_countsASentInviteEmail() {
        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"officer@tenant.in","name":"Asha",
                 "role":"SECTION_OFFICER","inviteLink":"https://x.in/a?t=tok","expiryHours":48}
                """);

        assertThat(sendCount("EMAIL", "SEND_INVITE_EMAIL", NotificationMetrics.SENT)).isEqualTo(1.0);
    }

    /**
     * Counted per attempt on purpose. The container is about to retry, so the same send may increment
     * this several times — which is what makes the rate meaningful when a provider is down.
     */
    @Test
    void route_countsATransientEmailFailure_andStillRethrowsForContainerRetry() {
        doThrow(new TransientMailException("SendGrid returned HTTP 503", new RuntimeException()))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"officer@tenant.in",
                 "resetLink":"https://x.in/r?t=tok","expiryMinutes":30}
                """))
                .isInstanceOf(RuntimeException.class);

        assertThat(sendCount("EMAIL", "SEND_PASSWORD_RESET_EMAIL", NotificationMetrics.FAILED)).isEqualTo(1.0);
    }

    @Test
    void route_countsAPermanentEmailFailureAsRejected() {
        doThrow(new PermanentMailException("SendGrid returned HTTP 401", new RuntimeException()))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"officer@tenant.in",
                 "resetLink":"https://x.in/r?t=tok","expiryMinutes":30}
                """);

        assertThat(sendCount("EMAIL", "SEND_PASSWORD_RESET_EMAIL", NotificationMetrics.REJECTED)).isEqualTo(1.0);
    }

    // ───────────────── account-email failure notices (shape + accounting) ─────────────────

    /**
     * The record is a failure notice, not a replayable command. An invite link is a single-use bearer
     * credential — whoever holds the URL can take the account — and this topic is long-retention and
     * consumed by nothing. Recovery is to re-issue from user-service, which mints a fresh link, so the
     * record carries who to re-issue to and deliberately not the link itself.
     */
    @Test
    @SuppressWarnings("unchecked")
    void route_failureNoticeIdentifiesTheRecipientButNeverCarriesTheInviteLink() {
        doThrow(new PermanentMailException("SendGrid returned HTTP 400", new RuntimeException()))
                .when(accountEmailService).sendInviteEmail(anyString(), anyString(), anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"officer@tenant.in","name":"Asha",
                 "role":"SECTION_OFFICER","inviteLink":"https://x.in/a?token=SECRET","expiryHours":48}
                """);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer).publishJson(eq("account-email-dlt"), payloadCaptor.capture());
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();

        assertThat(payload).containsEntry("to", "officer@tenant.in")
                .containsEntry("originalEventType", "SEND_INVITE_EMAIL")
                .containsEntry("recipientRole", "SECTION_OFFICER")
                .containsKey("failureId");
        // The whole point of the record's shape.
        assertThat(payload).doesNotContainKey("inviteLink");
        assertThat(payload.toString()).doesNotContain("SECRET");
    }

    /** Deterministic in recipient and flow, so repeat failures for one person collapse downstream. */
    @Test
    @SuppressWarnings("unchecked")
    void route_failureNoticeIdIsStableAcrossRepeatedFailuresForTheSameRecipient() {
        doThrow(new PermanentMailException("SendGrid returned HTTP 400", new RuntimeException()))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());
        String event = """
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"officer@tenant.in",
                 "resetLink":"https://x.in/r?t=tok","expiryMinutes":30}
                """;

        router.route(event);
        router.route(event);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer, times(2)).publishJson(eq("account-email-dlt"), payloadCaptor.capture());
        List<Object> payloads = payloadCaptor.getAllValues();
        assertThat(((Map<String, Object>) payloads.get(0)).get("failureId"))
                .isEqualTo(((Map<String, Object>) payloads.get(1)).get("failureId"));
    }

    /**
     * The accounting bug this closes: publishJson used to return without waiting for the broker, so a
     * rejected publish surfaced asynchronously and never reached the catch. The router took the silent
     * return as success and counted "deadlettered" — reporting a recoverable park for an email that was
     * actually lost, and leaving the one outcome that must page someone invisible.
     */
    @Test
    void route_countsADroppedEmail_whenTheFailureNoticeIsNotAcknowledgedByTheBroker() {
        doThrow(new PermanentMailException("SendGrid returned HTTP 401", new RuntimeException()))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());
        when(kafkaProducer.publishJson(eq("account-email-dlt"), any())).thenReturn(false);

        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"officer@tenant.in",
                 "resetLink":"https://x.in/r?t=tok","expiryMinutes":30}
                """);

        assertThat(deadLetterCount("dropped", "account-email-dlt")).isEqualTo(1.0);
        assertThat(deadLetterCount("deadlettered", "account-email-dlt")).isZero();
    }

    @Test
    void route_countsADeadLetteredEmail_whenTheBrokerAcknowledgesTheFailureNotice() {
        doThrow(new PermanentMailException("SendGrid returned HTTP 401", new RuntimeException()))
                .when(accountEmailService).sendPasswordResetEmail(anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"officer@tenant.in",
                 "resetLink":"https://x.in/r?t=tok","expiryMinutes":30}
                """);

        assertThat(deadLetterCount("deadlettered", "account-email-dlt")).isEqualTo(1.0);
        assertThat(deadLetterCount("dropped", "account-email-dlt")).isZero();
    }

    private double deadLetterCount(String outcome, String topic) {
        var counter = meterRegistry.find(KafkaConfig.DEADLETTER_COUNTER)
                .tag("outcome", outcome).tag("topic", topic).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // ───────────────────── SEND_LOGIN_OTP — SMS channel ────────────────────────

    @Test
    void route_sendsLoginOtp_viaSms_whenDeliveryChannelIsSms() {
        final boolean[] subscribed = {false};
        when(smsSender.sendOtp("919876500020", "123456", 5))
                .thenReturn(Mono.defer(() -> {
                    subscribed[0] = true;
                    return Mono.just(true);
                }));

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500020","expiryMinutes":5}
                """);

        verify(smsSender).sendOtp("919876500020", "123456", 5);
        assertThat(subscribed[0]).as("sendOtp Mono should be subscribed").isTrue();
        verifyNoInteractions(whatsAppChannel);
    }

    @Test
    void route_sendsLoginOtp_viaSms_defaultsExpiryToFive_whenExpiryMinutesIsZero() {
        final boolean[] subscribed = {false};
        when(smsSender.sendOtp("919876500021", "654321", 5))
                .thenReturn(Mono.defer(() -> {
                    subscribed[0] = true;
                    return Mono.just(true);
                }));

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"654321",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500021","expiryMinutes":0}
                """);

        verify(smsSender).sendOtp("919876500021", "654321", 5);
        assertThat(subscribed[0]).as("sendOtp Mono should be subscribed").isTrue();
    }

    @Test
    void route_sendsLoginOtp_viaSms_defaultsExpiryToFive_whenExpiryMinutesIsNegative() {
        final boolean[] subscribed = {false};
        when(smsSender.sendOtp("919876500022", "111222", 5))
                .thenReturn(Mono.defer(() -> {
                    subscribed[0] = true;
                    return Mono.just(true);
                }));

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"111222",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500022","expiryMinutes":-1}
                """);

        verify(smsSender).sendOtp("919876500022", "111222", 5);
        assertThat(subscribed[0]).as("sendOtp Mono should be subscribed").isTrue();
    }

    @Test
    void route_skipsLoginOtp_viaSms_whenPhoneIsBlank() {
        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"333444",
                 "deliveryChannel":"SMS","officerPhoneNumber":"","expiryMinutes":5}
                """);

        verifyNoInteractions(smsSender);
    }

    @Test
    void route_skipsLoginOtp_whenDeliveryChannelIsBlank() {
        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"555666",
                 "deliveryChannel":"","officerPhoneNumber":"919876500023"}
                """);

        verifyNoInteractions(whatsAppChannel, smsSender, glificWhatsAppService);
    }

    @Test
    void route_skipsLoginOtp_whenDeliveryChannelIsUnsupported() {
        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"777888",
                 "deliveryChannel":"TELEGRAM","officerPhoneNumber":"919876500024"}
                """);

        verifyNoInteractions(whatsAppChannel, smsSender, glificWhatsAppService);
    }

    // ────────── SEND_LOGIN_OTP / SMS — the handler waits for the outcome ──────────
    //
    // The handler used to call .subscribe() and return immediately, so the listener thread finished
    // the record while the HTTP call was still in flight and the offset committed against an outcome
    // nobody knew yet. A restart inside that window lost the OTP with no counter and no log line.
    // These four tests pin the replacement: block for a bounded time, classify what came back, and
    // never let it reach the container.

    @Test
    void route_waitsForTheSmsOtpOutcome_beforeReturning() {
        when(smsSender.sendOtp("919876500040", "123456", 5))
                .thenReturn(Mono.just(true).delayElement(Duration.ofMillis(200)));

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500040","expiryMinutes":5}
                """);

        // The whole point: fire-and-forget would return well before the 200ms delay elapsed and
        // leave this counter at zero.
        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.SENT)).isEqualTo(1.0);
    }

    /**
     * A provider that accepts the connection and then stalls. The wait has to be bounded — otherwise
     * one hung TLS connection parks an OTP listener thread until max.poll.interval.ms evicts the
     * consumer from its group.
     */
    @Test
    void route_countsFailed_andDoesNotThrow_whenTheSmsSendExceedsTheWaitBudget() {
        ReflectionTestUtils.setField(router, "smsSendTimeout", Duration.ofMillis(100));
        when(smsSender.sendOtp(anyString(), anyString(), anyInt())).thenReturn(Mono.never());

        assertThatCode(() -> router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500041","expiryMinutes":5}
                """)).doesNotThrowAnyException();

        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.FAILED)).isEqualTo(1.0);
        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.SENT)).isZero();
    }

    /**
     * An empty completion is not one of the three states {@code SmsSender} documents, so the outcome
     * is unknown — which is a failure, not the {@code rejected} that {@code Mono.just(false)} means.
     */
    @Test
    void route_countsFailed_whenTheSmsSendCompletesWithoutAnOutcome() {
        when(smsSender.sendOtp(anyString(), anyString(), anyInt())).thenReturn(Mono.empty());

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500042","expiryMinutes":5}
                """);

        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.FAILED)).isEqualTo(1.0);
        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.REJECTED)).isZero();
    }

    /**
     * Blocking makes the error reachable by the caller for the first time, so the swallow that used
     * to be belt-and-braces is now load-bearing: without it {@code route}'s outer catch would wrap
     * this and hand the container a retry of a login OTP.
     */
    @Test
    void route_doesNotRethrow_whenTheSmsSendErrors() {
        when(smsSender.sendOtp(anyString(), anyString(), anyInt()))
                .thenReturn(Mono.error(new RuntimeException("SMSCountry OTP send failed (server error)")));

        assertThatCode(() -> router.route("""
                {"eventType":"SEND_LOGIN_OTP","OTP":"123456",
                 "deliveryChannel":"SMS","officerPhoneNumber":"919876500043","expiryMinutes":5}
                """)).doesNotThrowAnyException();

        assertThat(sendCount("SMS", "SEND_LOGIN_OTP", NotificationMetrics.FAILED)).isEqualTo(1.0);
    }

    // ────────────── SEND_INVITE_EMAIL — STATE_ADMIN with stateName ──────────────

    @Test
    void route_dispatchesStateAdminInviteEmail_whenRoleIsStateAdminAndStateNamePresent() {
        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"sa@mp.gov.in",
                 "name":"Priya Sharma","role":"STATE_ADMIN",
                 "stateName":"Madhya Pradesh",
                 "inviteLink":"https://app.jalsoochak.in/activate?token=sa1","expiryHours":24}
                """);

        verify(accountEmailService).sendStateAdminInviteEmail(
                "sa@mp.gov.in", "Priya Sharma", "Madhya Pradesh",
                "https://app.jalsoochak.in/activate?token=sa1", 24);
        verify(accountEmailService, never()).sendInviteEmail(anyString(), anyString(), anyString(), anyString(), anyInt());
        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void route_routesToDlt_whenStateAdminInviteEmailThrows() {
        doThrow(new IllegalArgumentException("stateName must not be null or blank"))
                .when(accountEmailService)
                .sendStateAdminInviteEmail(anyString(), anyString(), anyString(), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"sa@mp.gov.in",
                 "name":"Priya Sharma","role":"STATE_ADMIN",
                 "stateName":"Madhya Pradesh",
                 "inviteLink":"https://link","expiryHours":24}
                """);

        verify(kafkaProducer).publishJson(eq("account-email-dlt"), argThat(p -> {
            String s = p.toString();
            return s.contains("ACCOUNT_EMAIL_FAILED") && s.contains("email_delivery_error");
        }));
    }

    @Test
    void route_fallsBackToGenericInviteEmail_whenStateAdminHasNoStateName() {
        // When role=STATE_ADMIN but stateName is absent/blank, the code falls through
        // to sendInviteEmail, which will throw (STATE_ADMIN requires stateName).
        // The exception is caught and routed to DLT.
        doThrow(new IllegalArgumentException("STATE_ADMIN invitations require a stateName"))
                .when(accountEmailService)
                .sendInviteEmail(anyString(), anyString(), eq("STATE_ADMIN"), anyString(), anyInt());

        router.route("""
                {"eventType":"SEND_INVITE_EMAIL","to":"sa@mp.gov.in",
                 "name":"Priya Sharma","role":"STATE_ADMIN",
                 "inviteLink":"https://link","expiryHours":24}
                """);

        verify(accountEmailService).sendInviteEmail(
                "sa@mp.gov.in", "Priya Sharma", "STATE_ADMIN", "https://link", 24);
        verify(kafkaProducer).publishJson(eq("account-email-dlt"), any());
    }

    // ── DAILY_REPORT_KPIS ────────────────────────────────────────────────────────

    private static final String DAILY_REPORT_JSON = """
            {"eventType":"DAILY_REPORT_KPIS","tenantId":1,"tenantSchema":"tenant_mp",
             "officerUserId":500,"officerUserType":"SECTION_OFFICER",
             "kpis":{"reportDate":"2026-07-07","previousDate":"2026-07-06","totalSchemes":10,
                     "yesterday":{"schemesSupplying":8,"schemesNotSupplying":2,"avgLpcd":55.0,"avgKld":1200.0,
                                  "regularSupplyPctWeek":80.0,"readingSubmissionPct":90.0,"anomalousCount":3},
                     "previousDay":{"schemesSupplying":7,"schemesNotSupplying":3,"avgLpcd":50.0,"avgKld":1100.0,
                                    "regularSupplyPctWeek":75.0,"readingSubmissionPct":85.0,"anomalousCount":4}}}
            """;

    @SuppressWarnings("unchecked")
    private void stubOfficerContact(Long whatsappId, String encTitle, String encPhone) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("whatsapp_connection_id", Long.class)).thenReturn(whatsappId);
                    when(rs.getString("title")).thenReturn(encTitle);
                    when(rs.getString("phone_number")).thenReturn(encPhone);
                    return List.of(rm.mapRow(rs, 0));
                });
    }

    @Test
    void handleDailyReport_generatesPdfAndSendsToSectionOfficer() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(whatsAppChannel.sendDailyReport(12345L, "https://minio/daily_report_x.pdf", "SECTION_OFFICER", LocalDate.of(2026, 7, 7), "Binod Nimoli"))
                .thenReturn(true);

        router.route(DAILY_REPORT_JSON);

        verify(dailyReportPdfService).generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList());
        verify(minioStorageService).upload(any(Path.class));
        verify(whatsAppChannel).sendDailyReport(12345L, "https://minio/daily_report_x.pdf", "SECTION_OFFICER", LocalDate.of(2026, 7, 7), "Binod Nimoli");
        // Stored contact present → no opt-in, no contact-registered event.
        verify(glificWhatsAppService, never()).optIn(anyString());
    }

    @Test
    void handleDailyReport_skipsSubDivisionalOfficerWhenSdoDisabled() {
        // Kill-switch: field left false in this test (default true in prod via @Value).
        ReflectionTestUtils.setField(router, "dailyReportSdoEnabled", false);
        String sdoJson = DAILY_REPORT_JSON.replace("SECTION_OFFICER", "SUB_DIVISIONAL_OFFICER");

        router.route(sdoJson);

        verifyNoInteractions(dailyReportPdfService);
        verify(whatsAppChannel, never()).sendDailyReport(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleDailyReport_sdoEnabled_resolvesSectionOfficerRowsAndSends() throws Exception {
        ReflectionTestUtils.setField(router, "dailyReportSdoEnabled", true);
        String sdoJson = """
                {"eventType":"DAILY_REPORT_KPIS","tenantId":1,"tenantSchema":"tenant_mp",
                 "officerUserId":500,"officerUserType":"SUB_DIVISIONAL_OFFICER",
                 "kpis":{"reportDate":"2026-07-07","previousDate":"2026-07-06","totalSchemes":10,
                         "yesterday":{"schemesSupplying":8,"schemesNotSupplying":2,"avgLpcd":55.0,"avgKld":1200.0,"regularSupplyPctWeek":80.0,"readingSubmissionPct":90.0,"anomalousCount":3},
                         "previousDay":{"schemesSupplying":7,"schemesNotSupplying":3,"avgLpcd":50.0,"avgKld":1100.0,"regularSupplyPctWeek":75.0,"readingSubmissionPct":85.0,"anomalousCount":4},
                         "sectionOfficerSummaries":[
                           {"officerUserId":601,"totalSchemes":154,"schemesSupplying":148,"schemesNotSupplying":6,"avgLpcd":67.0,"avgKld":18432.5,"regularSupplyPctWeek":32.0,"readingSubmissionPct":78.0,"anomalousCount":8},
                           {"officerUserId":602,"totalSchemes":90,"schemesSupplying":80,"schemesNotSupplying":10,"avgLpcd":55.0,"avgKld":9640.2,"regularSupplyPctWeek":60.0,"readingSubmissionPct":88.0,"anomalousCount":2}]}}
                """;

        // SDO's own contact (WHERE id = ?) — stored WhatsApp id.
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains(".user_table WHERE id = ?")),
                any(RowMapper.class), eq(500L)))
                .thenAnswer(inv -> {
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("whatsapp_connection_id", Long.class)).thenReturn(999L);
                    when(rs.getString("title")).thenReturn("enc-sdo");
                    when(rs.getString("phone_number")).thenReturn(null);
                    return List.of(rm.mapRow(rs, 0));
                });
        // Section Officer contacts (WHERE id IN (...)) — two rows.
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains(".user_table WHERE id IN")),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet r1 = mock(ResultSet.class);
                    when(r1.getLong("id")).thenReturn(601L);
                    when(r1.getObject("whatsapp_connection_id", Long.class)).thenReturn(null);
                    when(r1.getString("title")).thenReturn("enc-alice");
                    when(r1.getString("phone_number")).thenReturn("enc-alice-p");
                    ResultSet r2 = mock(ResultSet.class);
                    when(r2.getLong("id")).thenReturn(602L);
                    when(r2.getObject("whatsapp_connection_id", Long.class)).thenReturn(null);
                    when(r2.getString("title")).thenReturn("enc-bob");
                    when(r2.getString("phone_number")).thenReturn("enc-bob-p");
                    return List.of(rm.mapRow(r1, 0), rm.mapRow(r2, 1));
                });
        when(piiEncryptionService.safeDecrypt(any())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            if (v == null) {
                return null;
            }
            return switch (v) {
                case "enc-sdo" -> "SDO Kumar";
                case "enc-alice" -> "Alice";
                case "enc-alice-p" -> "919868595001";
                case "enc-bob" -> "Bob";
                case "enc-bob-p" -> "919868595002";
                default -> v;
            };
        });
        when(dailyReportPdfService.generate(any(), eq(500L), eq("SDO Kumar"), eq("SUB_DIVISIONAL_OFFICER"), anyList(), anyList()))
                .thenReturn("sdo.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/sdo.pdf");
        when(whatsAppChannel.sendDailyReport(999L, "https://minio/sdo.pdf", "SUB_DIVISIONAL_OFFICER", LocalDate.of(2026, 7, 7), "SDO Kumar")).thenReturn(true);

        router.route(sdoJson);

        ArgumentCaptor<List<DailyReportSectionOfficerRow>> cap = ArgumentCaptor.forClass(List.class);
        verify(dailyReportPdfService).generate(
                any(), eq(500L), eq("SDO Kumar"), eq("SUB_DIVISIONAL_OFFICER"), anyList(), cap.capture());
        verify(whatsAppChannel).sendDailyReport(999L, "https://minio/sdo.pdf", "SUB_DIVISIONAL_OFFICER", LocalDate.of(2026, 7, 7), "SDO Kumar");

        List<DailyReportSectionOfficerRow> soRows = cap.getValue();
        assertThat(soRows).hasSize(2);
        assertThat(soRows).extracting(DailyReportSectionOfficerRow::getOfficerName)
                .containsExactly("Alice", "Bob");
        assertThat(soRows.get(0).getOfficerMobile()).isEqualTo("919868595001");
        assertThat(soRows.get(0).getTotalSchemes()).isEqualTo(154);
        assertThat(soRows.get(0).getSchemesNotSupplying()).isEqualTo(6);
    }

    @Test
    void handleDailyReport_logsFailedGenerationOutcomeAndRethrows() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenThrow(new java.io.IOException("PDF write failed"));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NotificationEventRouter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // Rethrown (wrapped by route) so the Kafka container still retries the event.
            assertThatThrownBy(() -> router.route(DAILY_REPORT_JSON))
                    .hasRootCauseMessage("PDF write failed");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        // A generation failure must be counted like every other terminal outcome.
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("result=FAILED_GENERATION role=SECTION_OFFICER tenant=1 officer=500"));
        verify(minioStorageService, never()).upload(any(Path.class));
        verify(whatsAppChannel, never()).sendDailyReport(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void handleDailyReport_sdoKillSwitchMatchesRoleWithSurroundingWhitespace() {
        // The PDF layout and the Glific template both trim the role, so the kill-switch must too —
        // otherwise a padded role would render/send as an SDO while claiming to be suppressed.
        ReflectionTestUtils.setField(router, "dailyReportSdoEnabled", false);
        String sdoJson = DAILY_REPORT_JSON.replace("\"SECTION_OFFICER\"", "\"  SUB_DIVISIONAL_OFFICER  \"");

        router.route(sdoJson);

        verifyNoInteractions(dailyReportPdfService);
        verify(whatsAppChannel, never()).sendDailyReport(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void handleDailyReport_skipsWhenNoContactResolvable() {
        stubOfficerContact(null, null, null); // no whatsapp id, no phone
        when(piiEncryptionService.safeDecrypt(any())).thenReturn(null);

        router.route(DAILY_REPORT_JSON);

        verifyNoInteractions(dailyReportPdfService);
        verify(whatsAppChannel, never()).sendDailyReport(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void handleDailyReport_fallsBackToOptIn_andPublishesEvent_whenNoStoredContactId() throws Exception {
        // No stored whatsapp_connection_id, but an encrypted phone is present → opt-in fallback.
        stubOfficerContact(null, "enc-title", "enc-phone");
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(piiEncryptionService.safeDecrypt("enc-phone")).thenReturn("919876500024");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(glificWhatsAppService.optIn("919876500024")).thenReturn(88L);
        when(whatsAppChannel.sendDailyReport(88L, "https://minio/daily_report_x.pdf", "SECTION_OFFICER", LocalDate.of(2026, 7, 7), "Binod Nimoli"))
                .thenReturn(true);

        router.route(DAILY_REPORT_JSON);

        verify(dailyReportPdfService).generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList());
        verify(glificWhatsAppService).optIn("919876500024");
        verify(whatsAppChannel).sendDailyReport(88L, "https://minio/daily_report_x.pdf", "SECTION_OFFICER", LocalDate.of(2026, 7, 7), "Binod Nimoli");
        verify(kafkaProducer).publishJson(eq("common-topic"), argThat(event -> {
            String s = event.toString();
            return s.contains("WHATSAPP_CONTACT_REGISTERED") && s.contains("88");
        }));
    }

    /**
     * Regression: a live daily report with an opt-in that yields no contact id must stop at the
     * contact-resolution step. Sending with {@code contactId=0} made Glific answer
     * "Receiver does not exist", which the router turned into a rethrow and a Kafka retry loop that
     * stalled every other event on the partition.
     *
     * <p>The stop happens before the report is built: no PDF is rendered and nothing is uploaded, so a
     * dead end costs neither a render nor a MinIO round-trip. The absent {@code generate}/{@code upload}
     * stubs are part of the assertion — strict stubbing would flag them if the router still ran them.</p>
     */
    @Test
    void handleDailyReport_skipsWithoutRethrowing_whenOptInYieldsNoContactId() throws Exception {
        stubOfficerContact(null, "enc-title", "enc-phone");
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(piiEncryptionService.safeDecrypt("enc-phone")).thenReturn("919876500025");
        when(glificWhatsAppService.optIn("919876500025")).thenReturn(0L);
        when(glificWhatsAppService.isDailyReportDeliveryEnabled()).thenReturn(true);

        router.route(DAILY_REPORT_JSON);

        verifyNoInteractions(dailyReportPdfService);
        verify(minioStorageService, never()).upload(any(Path.class));
        verify(whatsAppChannel, never()).sendDailyReport(anyLong(), anyString(), anyString(), any(), any());
        verify(kafkaProducer, never()).publishJson(eq("common-topic"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleDailyReport_enrichesPriorityRowsFromOperationalSchema() throws Exception {
        // Priority Actions is hidden by default; enrichment only runs when the section is restored.
        ReflectionTestUtils.setField(router, "dailyReportOutageDetailSectionsEnabled", true);
        String json = """
                {"eventType":"DAILY_REPORT_KPIS","tenantId":1,"tenantSchema":"tenant_mp",
                 "officerUserId":500,"officerUserType":"SECTION_OFFICER",
                 "kpis":{"reportDate":"2026-07-07","previousDate":"2026-07-06","totalSchemes":10,
                         "yesterday":{"schemesSupplying":8,"schemesNotSupplying":2,"avgLpcd":55.0,"avgKld":1200.0,"regularSupplyPctWeek":80.0,"readingSubmissionPct":90.0,"anomalousCount":3},
                         "previousDay":{"schemesSupplying":7,"schemesNotSupplying":3,"avgLpcd":50.0,"avgKld":1100.0,"regularSupplyPctWeek":75.0,"readingSubmissionPct":85.0,"anomalousCount":4},
                         "priorityActions":[{"schemeId":7,"issue":"Pump Failure","daysNoSupply":5}]}}
                """;

        // Officer contact (by id) — has a stored WhatsApp id, so no opt-in.
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains(".user_table WHERE id = ?")),
                any(RowMapper.class), eq(500L)))
                .thenAnswer(inv -> {
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("whatsapp_connection_id", Long.class)).thenReturn(12345L);
                    when(rs.getString("title")).thenReturn("enc-officer");
                    when(rs.getString("phone_number")).thenReturn(null);
                    return List.of(rm.mapRow(rs, 0));
                });
        // Scheme labels (batched by id).
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains(".scheme_master_table WHERE id IN")),
                any(RowMapper.class), eq(7)))
                .thenAnswer(inv -> {
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getInt("id")).thenReturn(7);
                    when(rs.getString("scheme_name")).thenReturn("Rampur WSS");
                    when(rs.getString("centre_scheme_id")).thenReturn("IMIS-7");
                    return List.of(rm.mapRow(rs, 0));
                });
        // Pump operators (batched by scheme) — two operators.
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("PUMP_OPERATOR")),
                any(RowMapper.class), eq(7)))
                .thenAnswer(inv -> {
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet r1 = mock(ResultSet.class);
                    when(r1.getInt("scheme_id")).thenReturn(7);
                    when(r1.getString("title")).thenReturn("enc-n1");
                    when(r1.getString("phone_number")).thenReturn("enc-p1");
                    ResultSet r2 = mock(ResultSet.class);
                    when(r2.getInt("scheme_id")).thenReturn(7);
                    when(r2.getString("title")).thenReturn("enc-n2");
                    when(r2.getString("phone_number")).thenReturn("enc-p2");
                    return List.of(rm.mapRow(r1, 0), rm.mapRow(r2, 1));
                });
        when(piiEncryptionService.safeDecrypt(any())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            if (v == null) {
                return null;
            }
            return switch (v) {
                case "enc-officer" -> "Binod";
                case "enc-n1" -> "Ramesh";
                case "enc-p1" -> "919000000001";
                case "enc-n2" -> "Suresh";
                case "enc-p2" -> "919000000002";
                default -> v;
            };
        });
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("f.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/f.pdf");
        when(whatsAppChannel.sendDailyReport(12345L, "https://minio/f.pdf", "SECTION_OFFICER", LocalDate.of(2026, 7, 7), "Binod")).thenReturn(true);

        router.route(json);

        ArgumentCaptor<List<DailyReportPriorityRow>> cap = ArgumentCaptor.forClass(List.class);
        verify(dailyReportPdfService).generate(any(), eq(500L), eq("Binod"), eq("SECTION_OFFICER"), cap.capture(), anyList());
        List<DailyReportPriorityRow> rows = cap.getValue();
        assertThat(rows).hasSize(1);
        DailyReportPriorityRow row = rows.get(0);
        assertThat(row.getScheme()).isEqualTo("Rampur WSS");
        assertThat(row.getImisId()).isEqualTo("IMIS-7");
        assertThat(row.getJalMitraNames()).isEqualTo("Ramesh, Suresh");
        assertThat(row.getJalMitraMobiles()).isEqualTo("919000000001, 919000000002");
        assertThat(row.getIssue()).isEqualTo("Pump Failure");
        assertThat(row.getRemarks()).isEqualTo("No water supply for past 5 days");
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleDailyReport_withOutageSectionsDisabled_skipsPriorityRowEnrichment() throws Exception {
        // Default state: the Priority Actions section is hidden, so no rows are built — and crucially
        // the Jal Mitra name/mobile lookups (PII decryption) are never performed.
        String json = """
                {"eventType":"DAILY_REPORT_KPIS","tenantId":1,"tenantSchema":"tenant_mp",
                 "officerUserId":500,"officerUserType":"SECTION_OFFICER",
                 "kpis":{"reportDate":"2026-07-07","previousDate":"2026-07-06","totalSchemes":10,
                         "yesterday":{"schemesSupplying":8,"schemesNotSupplying":2,"avgLpcd":55.0,"avgKld":1200.0,"regularSupplyPctWeek":80.0,"readingSubmissionPct":90.0,"anomalousCount":3},
                         "previousDay":{"schemesSupplying":7,"schemesNotSupplying":3,"avgLpcd":50.0,"avgKld":1100.0,"regularSupplyPctWeek":75.0,"readingSubmissionPct":85.0,"anomalousCount":4},
                         "priorityActions":[{"schemeId":7,"issue":"Pump Failure","daysNoSupply":5}]}}
                """;

        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains(".user_table WHERE id = ?")),
                any(RowMapper.class), eq(500L)))
                .thenAnswer(inv -> {
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("whatsapp_connection_id", Long.class)).thenReturn(12345L);
                    when(rs.getString("title")).thenReturn("enc-officer");
                    when(rs.getString("phone_number")).thenReturn(null);
                    return List.of(rm.mapRow(rs, 0));
                });
        when(piiEncryptionService.safeDecrypt("enc-officer")).thenReturn("Binod");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("f.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/f.pdf");
        when(whatsAppChannel.sendDailyReport(12345L, "https://minio/f.pdf", "SECTION_OFFICER", LocalDate.of(2026, 7, 7), "Binod")).thenReturn(true);

        router.route(json);

        ArgumentCaptor<List<DailyReportPriorityRow>> cap = ArgumentCaptor.forClass(List.class);
        verify(dailyReportPdfService).generate(any(), eq(500L), eq("Binod"), eq("SECTION_OFFICER"), cap.capture(), anyList());
        assertThat(cap.getValue()).isEmpty();
        verify(jdbcTemplate, never()).query(argThat(sql -> sql != null && sql.contains("PUMP_OPERATOR")),
                any(RowMapper.class), eq(7));
    }
}
