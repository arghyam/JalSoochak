package org.arghyam.jalsoochak.message.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.LocalDate;
import java.sql.ResultSet;
import java.util.List;

import org.arghyam.jalsoochak.message.channel.DailyReportDeliveryMode;
import org.arghyam.jalsoochak.message.channel.DailyReportSendOutcome;
import org.arghyam.jalsoochak.message.channel.GlificSendResult;
import org.arghyam.jalsoochak.message.channel.GlificSendStage;
import org.arghyam.jalsoochak.message.channel.GlificWhatsAppService;
import org.arghyam.jalsoochak.message.channel.SmsSender;
import org.arghyam.jalsoochak.message.channel.WhatsAppChannel;
import org.arghyam.jalsoochak.message.dto.DailyReportPriorityRow;
import org.arghyam.jalsoochak.message.dto.DailyReportSectionOfficerRow;
import org.arghyam.jalsoochak.message.kafka.KafkaProducer;
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

    @Test
    void route_rethrowsException_whenLoginOtpDeliveryFails() {
        when(glificWhatsAppService.optIn(anyString())).thenReturn(55L);
        when(whatsAppChannel.sendLoginOtp(anyLong(), anyString()))
                .thenReturn(false);

        assertThatThrownBy(() -> router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO","OTP":"222222",
                 "deliveryChannel":"WHATSAPP","glific_id":"","officerPhoneNumber":"919000000002"}
                """))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");
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
                .thenReturn(acceptedSend());

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
        when(whatsAppChannel.sendDailyReport(999L, "https://minio/sdo.pdf", "SUB_DIVISIONAL_OFFICER", LocalDate.of(2026, 7, 7), "SDO Kumar"))
                .thenReturn(acceptedSend());

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
                .thenReturn(acceptedSend());

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
        when(whatsAppChannel.sendDailyReport(12345L, "https://minio/f.pdf", "SECTION_OFFICER", LocalDate.of(2026, 7, 7), "Binod"))
                .thenReturn(acceptedSend());

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
        when(whatsAppChannel.sendDailyReport(12345L, "https://minio/f.pdf", "SECTION_OFFICER", LocalDate.of(2026, 7, 7), "Binod"))
                .thenReturn(acceptedSend());

        router.route(json);

        ArgumentCaptor<List<DailyReportPriorityRow>> cap = ArgumentCaptor.forClass(List.class);
        verify(dailyReportPdfService).generate(any(), eq(500L), eq("Binod"), eq("SECTION_OFFICER"), cap.capture(), anyList());
        assertThat(cap.getValue()).isEmpty();
        verify(jdbcTemplate, never()).query(argThat(sql -> sql != null && sql.contains("PUMP_OPERATOR")),
                any(RowMapper.class), eq(7));
    }


    // ───────────────── delivery-status join keys on the SENT line ─────────────────

    /**
     * {@code result=SENT} means Glific ACCEPTED the send, not that WhatsApp delivered it. The
     * {@code glificMsgId} on this line is the only join key that lets the delivery status Gupshup and
     * Meta later report back to Glific be matched to this officer — losing it breaks reconciliation
     * silently, so it is asserted rather than assumed.
     */
    @Test
    void handleDailyReport_sentLineCarriesTheGlificJoinKeys() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(whatsAppChannel.sendDailyReport(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(acceptedSend());

        String sent = captureRouterLog(DAILY_REPORT_JSON, "result=SENT");

        assertThat(sent)
                .contains("glificMsgId=241952654")
                .contains("glificContactId=12345")
                .contains("mode=LINK")
                .contains("templateId=880557")
                .contains("stage=GLIFIC_ACCEPTED");
    }

    /**
     * The counting grep for the adjacent run
     * {@code result=… role=… tenant=… officer=…}. Every new field must therefore be appended after
     * {@code officer=}, never inserted between them, or a year of documented one-liners breaks.
     */
    @Test
    void handleDailyReport_sentLinePreservesTheFieldAdjacencyTheCountingRecipesRelyOn() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(whatsAppChannel.sendDailyReport(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(acceptedSend());

        String sent = captureRouterLog(DAILY_REPORT_JSON, "result=SENT");

        assertThat(sent).containsPattern("result=SENT role=SECTION_OFFICER tenant=1 officer=500");
    }

    /**
     * The 20 Aug 2026 incident collapsed every cause into one FAILED_DELIVERY token. The stage says
     * which half of the handoff broke, and Glific's own error key comes with it.
     */
    @Test
    void handleDailyReport_failedDeliveryLineCarriesTheStageAndGlificErrorKey() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(whatsAppChannel.sendDailyReport(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(DailyReportSendOutcome.failed(
                        GlificSendStage.MEDIA_REGISTER, "media", "(#131053) Media upload error"));

        String failed = captureRouterLogExpectingRethrow(DAILY_REPORT_JSON, "result=FAILED_DELIVERY");

        assertThat(failed)
                .containsPattern("result=FAILED_DELIVERY role=SECTION_OFFICER tenant=1 officer=500")
                .contains("stage=MEDIA_REGISTER")
                .contains("glificErrorKey=media");
    }

    /**
     * A {@code CONFIG} failure never reached Glific: the template id, contact id or MinIO URL prefix is
     * wrong on our side. It is a definite rejection — so it keeps {@code result=FAILED_DELIVERY} — but
     * one no retry can repair, so redriving it only stalls the partition until someone changes
     * configuration. Terminal, not rethrown.
     */
    @Test
    void handleDailyReport_aConfigFailureIsCountedAsFailedButNotRetried() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(whatsAppChannel.sendDailyReport(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(DailyReportSendOutcome.failed(
                        GlificSendStage.CONFIG, null, "daily report LINK template id is not configured"));

        // captureRouterLogs fails the test if the router rethrows — which is the property under test.
        List<String> lines = captureRouterLogs(DAILY_REPORT_JSON);

        assertThat(lines).filteredOn(l -> l.contains("result=FAILED_DELIVERY")).singleElement()
                .satisfies(line -> assertThat(line)
                        .containsPattern("result=FAILED_DELIVERY role=SECTION_OFFICER tenant=1 officer=500")
                        .contains("stage=CONFIG"));
        assertThat(lines)
                .anyMatch(l -> l.contains("stage=CONFIG") && l.contains("(non-retryable)"))
                .noneMatch(l -> l.contains("result=DELIVERY_UNCONFIRMED"));
    }

    /**
     * A dry-run is accepted but nothing was sent, so it gets its own result token. Sharing
     * {@code result=SENT} with a real send meant a fully muted deployment counted as one that delivered
     * reports — and the line carried a {@code stage=GLIFIC_ACCEPTED} that never happened.
     */
    @Test
    void handleDailyReport_suppressedSendIsNotCountedAsSent() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(whatsAppChannel.sendDailyReport(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(DailyReportSendOutcome.accepted(
                        GlificSendResult.suppressed(DailyReportDeliveryMode.LINK)));

        List<String> lines = captureRouterLogs(DAILY_REPORT_JSON);

        assertThat(lines).noneMatch(l -> l.contains("result=SENT"));
        assertThat(lines).filteredOn(l -> l.contains("result=SUPPRESSED")).singleElement()
                .satisfies(line -> assertThat(line)
                        .containsPattern("result=SUPPRESSED role=SECTION_OFFICER tenant=1 officer=500")
                        .contains("mode=LINK")
                        .doesNotContain("stage=GLIFIC_ACCEPTED")
                        .doesNotContain("glificMsgId="));
    }

    /**
     * A {@code block()} timeout is the one failure a retry makes worse: Glific may already have created
     * and sent the message, so re-driving the event delivers the officer a second copy of the same
     * report. It is recorded for reconciliation and swallowed, not rethrown for the Kafka container.
     */
    @Test
    void handleDailyReport_aTimeoutIsRecordedButNotRetried() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(whatsAppChannel.sendDailyReport(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(DailyReportSendOutcome.failed(
                        GlificSendStage.TIMEOUT, null, "Timeout on blocking read for 30000 MILLISECONDS"));

        List<String> lines = captureRouterLogs(DAILY_REPORT_JSON);

        // One terminal line, and it is the unconfirmed one. Also emitting result=FAILED_DELIVERY counted
        // the same send twice and put a send that may well have arrived into the definite-failure total.
        assertThat(lines).noneMatch(l -> l.contains("result=FAILED_DELIVERY"));
        assertThat(lines).filteredOn(l -> l.contains("result=DELIVERY_UNCONFIRMED")).singleElement()
                .satisfies(line -> assertThat(line)
                        .containsPattern("result=DELIVERY_UNCONFIRMED role=SECTION_OFFICER tenant=1 officer=500")
                        .contains("stage=TIMEOUT")
                        .contains("(non-retryable)"));
    }

    /**
     * The same ambiguity, reached the other way: Glific reported no errors but returned no
     * {@code message.id}, so it holds a message we can never match a status to. A retry is a guaranteed
     * duplicate, which is why this stage joins TIMEOUT rather than being rethrown.
     */
    @Test
    void handleDailyReport_aSendWithNoMessageIdIsRecordedButNotRetried() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(whatsAppChannel.sendDailyReport(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(DailyReportSendOutcome.failed(GlificSendStage.SEND_NO_MESSAGE_ID, null,
                        "Glific accepted sendHsmMessage but returned no message.id"));

        List<String> lines = captureRouterLogs(DAILY_REPORT_JSON);

        assertThat(lines)
                .anyMatch(l -> l.contains("result=DELIVERY_UNCONFIRMED")
                        && l.contains("stage=SEND_NO_MESSAGE_ID"))
                .noneMatch(l -> l.contains("result=FAILED_DELIVERY"));
    }

    /** Every other failure stage still rethrows, so the Kafka container can apply its retry policy. */
    @Test
    void handleDailyReport_aRejectedSendStillRethrowsForRetry() throws Exception {
        stubOfficerContact(12345L, "enc-title", null);
        when(piiEncryptionService.safeDecrypt("enc-title")).thenReturn("Binod Nimoli");
        when(dailyReportPdfService.generate(any(), eq(500L), eq("Binod Nimoli"), eq("SECTION_OFFICER"), anyList(), anyList()))
                .thenReturn("daily_report_x.pdf");
        when(minioStorageService.upload(any(Path.class))).thenReturn("https://minio/daily_report_x.pdf");
        when(whatsAppChannel.sendDailyReport(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(DailyReportSendOutcome.failed(
                        GlificSendStage.MEDIA_REGISTER, "media", "(#131053) Media upload error"));

        assertThatThrownBy(() -> router.route(DAILY_REPORT_JSON))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");
    }

    /**
     * Routes the event with a log appender attached and returns the first line containing
     * {@code needle}. Fails on a router exception — see {@link #captureRouterLogs(String)}.
     */
    private String captureRouterLog(String json, String needle) {
        return firstLineContaining(captureRouterLogs(json), needle);
    }

    /** {@link #captureRouterLog} for the stages that rethrow for retry by design. */
    private String captureRouterLogExpectingRethrow(String json, String needle) {
        return firstLineContaining(captureRouterLogsExpectingRethrow(json), needle);
    }

    private static String firstLineContaining(List<String> lines, String needle) {
        return lines.stream()
                .filter(m -> m.contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line containing '" + needle + "'; lines were "
                        + lines));
    }

    /**
     * Every line the router logs while routing one event. Needed wherever the assertion is about which
     * result token was <em>not</em> emitted — a suppressed send must produce no {@code result=SENT}
     * line at all, which no single-line lookup can show.
     *
     * <p>Fails the test if the router throws. Swallowing it here made every non-retryable path
     * un-assertable: a stage that was supposed to be terminal could start rethrowing — stalling the
     * Kafka partition on an event no retry can repair — and these tests would still pass, because the
     * log lines they check are written before the throw. Tests that <em>want</em> the throw say so with
     * {@link #captureRouterLogsExpectingRethrow}.</p>
     */
    private List<String> captureRouterLogs(String json) {
        return captureRouterLogs(json, false);
    }

    /**
     * Same capture, for the failure stages that rethrow so the Kafka container can retry
     * ({@code MEDIA_REGISTER}, {@code SEND}). Explicit, so the throw is an asserted property of those
     * tests rather than something silently tolerated in all of them.
     */
    private List<String> captureRouterLogsExpectingRethrow(String json) {
        return captureRouterLogs(json, true);
    }

    private List<String> captureRouterLogs(String json, boolean expectRethrow) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NotificationEventRouter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        RuntimeException thrown = null;
        try {
            router.route(json);
        } catch (RuntimeException e) {
            thrown = e;
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        List<String> lines = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
        if (thrown != null && !expectRethrow) {
            throw new AssertionError("router rethrew, so this event would be retried; use"
                    + " captureRouterLogsExpectingRethrow if that is intended. Lines were " + lines, thrown);
        }
        if (thrown == null && expectRethrow) {
            throw new AssertionError("router did not rethrow, so the Kafka container never retries this"
                    + " event. Lines were " + lines);
        }
        return lines;
    }

    /**
     * A successful Glific acceptance, carrying the message id the router now logs. Glific returns
     * {@code message { id }} on every send and the router puts it on the {@code result=SENT} line —
     * it is the join key the delivery-status reconciliation matches back to this officer.
     */
    private static DailyReportSendOutcome acceptedSend() {
        return DailyReportSendOutcome.accepted(
                new GlificSendResult("241952654", "880557", DailyReportDeliveryMode.LINK));
    }
}