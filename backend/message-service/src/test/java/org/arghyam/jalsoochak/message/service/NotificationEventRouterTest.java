package org.arghyam.jalsoochak.message.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.arghyam.jalsoochak.message.channel.GlificWhatsAppService;
import org.arghyam.jalsoochak.message.channel.WhatsAppChannel;
import org.arghyam.jalsoochak.message.kafka.KafkaProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
    private MinioStorageService minioStorageService;

    @Mock
    private MessageTemplateService messageTemplateService;

    @Mock
    private AccountEmailService accountEmailService;

    @Mock
    private JdbcTemplate jdbcTemplate;

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
    }

    /**
     * Helper method to stub jdbcTemplate.query for user lookup.
     * Centralizes the SQL matcher and return list handling.
     */
    @SuppressWarnings("unchecked")
    private void stubUserLookup(String tenantCode, String phone, List<Long> returnList) {
        when(jdbcTemplate.query(
                argThat(sql -> sql.contains("FROM tenant_" + tenantCode + ".user_table") && sql.contains("WHERE phone_number = ?")),
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

    // ───────────────────────────── SEND_LOGIN_OTP ──────────────────────────────

    @Test
    void route_sendsLoginOtp_usingStoredGlificId() {
        when(whatsAppChannel.sendLoginOtp(42L, "123456")).thenReturn(true);

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO Singh",
                 "OTP":"123456","glific_id":"42","officerPhoneNumber":"919876500010"}
                """);

        verify(whatsAppChannel).sendLoginOtp(42L, "123456");
        verify(glificWhatsAppService, never()).optIn(anyString());
    }

    @Test
    void route_sendsLoginOtp_usingOptIn_whenGlificIdAbsent() {
        when(glificWhatsAppService.optIn("919876500010")).thenReturn(77L);
        when(whatsAppChannel.sendLoginOtp(77L, "654321")).thenReturn(true);

        router.route("""
                {"eventType":"SEND_LOGIN_OTP","officerName":"SO Singh",
                 "OTP":"654321","glific_id":"","officerPhoneNumber":"919876500010"}
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
                 "glific_id":"","officerPhoneNumber":"919000000002"}
                """))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification event processing failed");
    }

    // ──────────────────────── SEND_WELCOME_MESSAGE ─────────────────────────────

    @Test
    void route_sendsWelcomeMessage_whenContactIdFound() {
        stubUserLookup("mp", "919111111111", List.of(88L));

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE","tenantCode":"mp",
                 "pumpOperatorPhones":["919111111111"]}
                """);

        verify(glificWhatsAppService).startWelcomeFlow(88L);
        verify(kafkaProducer, never()).publishJson(eq("welcome-message-dlt"), any());
    }

    @Test
    void route_routesToDlt_whenNoContactIdFound() {
        stubUserLookup("mp", "919222222222", List.of());

        router.route("""
                {"eventType":"SEND_WELCOME_MESSAGE","tenantCode":"mp",
                 "pumpOperatorPhones":["919222222222"]}
                """);

        verify(glificWhatsAppService, never()).startWelcomeFlow(anyLong());
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
}