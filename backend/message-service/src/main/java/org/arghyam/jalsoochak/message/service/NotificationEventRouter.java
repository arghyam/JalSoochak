package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.channel.GlificWhatsAppService;
import org.arghyam.jalsoochak.message.channel.SmsCountryService;
import org.arghyam.jalsoochak.message.channel.WhatsAppChannel;
import org.arghyam.jalsoochak.message.dto.OperatorEscalationDetail;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.DailyReportPriorityRow;
import org.arghyam.jalsoochak.message.event.InviteEmailEvent;
import org.arghyam.jalsoochak.message.event.ResetPasswordEmailEvent;
import org.arghyam.jalsoochak.message.event.WhatsAppContactRegisteredEvent;
import org.arghyam.jalsoochak.message.kafka.KafkaProducer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Routes incoming Kafka JSON messages to the appropriate notification handler
 * based on the {@code eventType} field.
 *
 * <ul>
 *   <li>{@code NUDGE} — fetches the localized message from tenant config and
 *       sends it as a WhatsApp HSM to the operator.</li>
 *   <li>{@code ESCALATION} — generates a PDF, uploads it to MinIO, fetches
 *       the localized body text, and sends a document HSM to the officer.</li>
 *   <li>{@code STAFF_SYNC_COMPLETED} — onboards pump operators into Glific and
 *       publishes {@code WHATSAPP_CONTACT_REGISTERED} events so tenant-service
 *       can persist the contact IDs.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationEventRouter {

    private static final String COMMON_TOPIC = "common-topic";

    /**
     * Dead-letter topic for {@code SEND_WELCOME_MESSAGE} per-phone failures.
     *
     * <p>Messages are published here when a single phone cannot be processed
     * (missing {@code whatsapp_connection_id} or a Glific API error) so that
     * already-succeeded phones in the same batch are not re-sent by Kafka retry.
     *
     * <p>This service intentionally does <em>not</em> consume this topic.
     * Re-consuming from the same service that produces here would create an
     * unbounded retry loop. Instead, configure external monitoring/alerting
     * (e.g. a Kafka consumer lag alert or a separate ops consumer) on
     * {@code welcome-message-dlt} to detect and replay failed records.
     * Each dead-lettered record carries a {@code retryId} (UUID) field for
     * idempotent downstream reprocessing.
     */
    private static final String WELCOME_DLT_TOPIC = "welcome-message-dlt";

    /**
     * Dead-letter topic for {@code SEND_INVITE_EMAIL}, {@code SEND_REINVITE_EMAIL},
     * and {@code SEND_PASSWORD_RESET_EMAIL} per-recipient failures.
     *
     * <p>Messages are published here — and the handler returns without rethrowing —
     * for all failure modes: malformed event payload, missing required fields
     * ({@code to}, {@code inviteLink}, {@code resetLink}), or an SMTP-level error.
     *
     * <p>Email delivery is <em>not</em> idempotent: rethrowing on failure would
     * trigger Kafka's retry/back-off policy and could cause duplicate emails to be
     * sent to the same recipient. Routing to this DLT instead lets the Kafka
     * container move on while preserving the failed record for ops investigation
     * and controlled replay.
     *
     * <p>This service intentionally does <em>not</em> consume this topic.
     * Re-consuming from the same service that produces here would create an
     * unbounded retry loop. Instead, configure external monitoring/alerting
     * (e.g. a Kafka consumer lag alert or a separate ops consumer) on
     * {@code account-email-dlt} to detect and replay failed records.
     * Each dead-lettered record carries a {@code retryId} (UUID) field for
     * idempotent downstream reprocessing, and an {@code originalEventType} field
     * so the replaying consumer can re-route to the correct handler.
     */
    private static final String ACCOUNT_EMAIL_DLT_TOPIC = "account-email-dlt";

    private final ObjectMapper objectMapper;
    private final WhatsAppChannel whatsAppChannel;
    private final GlificWhatsAppService glificWhatsAppService;
    private final SmsCountryService smsCountryService;
    private final KafkaProducer kafkaProducer;
    private final EscalationPdfService escalationPdfService;
    private final DailyReportPdfService dailyReportPdfService;
    private final MinioStorageService minioStorageService;
    private final MessageTemplateService messageTemplateService;
    private final AccountEmailService accountEmailService;
    private final JdbcTemplate jdbcTemplate;
    private final PiiEncryptionService piiEncryptionService;

    @Value("${escalation.report.dir:/tmp/escalation-reports/}")
    private String reportDir;

    @Value("${app.base-url:http://localhost:8085}")
    private String baseUrl;

    /**
     * Gates delivery of the SUB_DIVISIONAL_OFFICER daily report. Defaults to {@code false} because
     * the SDO report layout is not yet specified (the sample docx is the SECTION_OFFICER template);
     * SDO events are computed and logged but not sent until an SDO layout is agreed and this is enabled.
     */
    @Value("${app.daily-report.sdo-enabled:false}")
    private boolean dailyReportSdoEnabled;

    private static final String SCHEMA_PATTERN = "^[a-z0-9_]+$";

    @PostConstruct
    void validateBaseUrl() {
        if (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")) {
            log.warn("[Router] app.base-url is set to a local address ('{}')."
                    + " PDF links embedded in escalation WhatsApp messages will be unreachable by Glific."
                    + " Set the 'app.base-url' property to a publicly reachable URL"
                    + " (e.g., 'APP_BASE_URL=https://<id>.ngrok.io' for demos,"
                    + " or your server's public hostname in production) before sending escalation reports.",
                    baseUrl);
        }
    }

    /**
     * Routes the message and re-throws any processing exception so the Kafka
     * container's error handler can apply its retry/back-off policy and, if
     * configured, forward the payload to a dead-letter topic.
     *
     * <p>Unknown {@code eventType} values are silently skipped — they are
     * permanent non-retryable conditions and must not cause infinite retries.</p>
     */
    public void route(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String eventType = root.path("eventType").asText("");

            switch (eventType.toUpperCase()) {
                case "NUDGE" -> handleNudge(root);
                case "ESCALATION" -> handleEscalation(root);
                case "DAILY_REPORT_KPIS" -> handleDailySituationReport(root);
                case "STAFF_SYNC_COMPLETED" -> handleStaffSyncCompleted(root);
                case "UPDATE_USER_LANGUAGE" -> handleUpdateUserLanguage(root);
                case "SEND_WELCOME_MESSAGE" -> handleSendWelcomeMessage(root);
                case "SEND_WELCOME_MESSAGE_ADMIN" -> handleSendWelcomeMessageAdmin(root);
                case "SEND_LOGIN_OTP" -> handleSendLoginOtp(root);
                case "SEND_INVITE_EMAIL" -> handleInviteEmail(root);
                case "SEND_REINVITE_EMAIL" -> handleReinviteEmail(root);
                case "SEND_PASSWORD_RESET_EMAIL" -> handlePasswordResetEmail(root);
                default -> log.warn("[Router] Unknown eventType '{}', ignoring message", eventType);
            }
        } catch (Exception e) {
            log.error("[Router] Failed to process Kafka message, rethrowing for container retry/DLT: {}",
                    e.getMessage(), e);
            throw new RuntimeException("Notification event processing failed", e);
        }
    }

    private void handleNudge(JsonNode root) {
        String phone = root.path("recipientPhone").asText("");
        String operatorName = root.path("operatorName").asText("Operator");
        String tenantSchema = root.path("tenantSchema").asText("");
        long userId = root.path("userId").asLong(0);
        long storedId = root.path("whatsappConnectionId").asLong(0);

        if (storedId <= 0 && phone.isBlank()) {
            log.warn("[Router/NUDGE] recipientPhone and whatsappConnectionId are both missing, skipping");
            return;
        }

        String todayDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy"));

        long contactId;
        if (storedId > 0) {
            contactId = storedId;
        } else {
            contactId = glificWhatsAppService.optIn(phone);
            if (!tenantSchema.isBlank() && userId > 0 && contactId > 0) {
                kafkaProducer.publishJson(COMMON_TOPIC,
                        WhatsAppContactRegisteredEvent.builder()
                                .eventType("WHATSAPP_CONTACT_REGISTERED")
                                .tenantSchema(tenantSchema)
                                .userId(userId)
                                .contactId(contactId)
                                .build());
            }
        }

        boolean sent = whatsAppChannel.sendNudgeViaFlow(contactId, operatorName, todayDate);
        if (!sent) {
            throw new IllegalStateException("[Router/NUDGE] WhatsApp nudge flow initiation failed");
        }
        log.info("[Router/NUDGE] → FLOW INITIATED");
        log.debug("[Router/NUDGE] phone={} → FLOW INITIATED", phone);
    }

    private void handleStaffSyncCompleted(JsonNode root) {
        JsonNode operatorsNode = root.path("pumpOperators");
        int glificLanguageId = root.path("glificLanguageId").asInt(0);
        String tenantSchema = root.path("tenantSchema").asText("");

        if (!operatorsNode.isArray() || operatorsNode.isEmpty()) {
            log.warn("[Router/STAFF_SYNC] pumpOperators is empty, skipping");
            return;
        }
        if (glificLanguageId == 0) {
            log.warn("[Router/STAFF_SYNC] glificLanguageId missing or zero, skipping");
            return;
        }

        int success = 0, failed = 0;
        for (JsonNode opNode : operatorsNode) {
            String phone = opNode.path("phone").asText("");
            long userId = opNode.path("userId").asLong(0);
            if (phone.isBlank()) {
                log.error("[Router/STAFF_SYNC] Operator userId={} has blank phone, skipping", userId);
                failed++;
                continue;
            }
            try {
                long contactId = whatsAppChannel.onboardOperator(phone, glificLanguageId);
                if (!tenantSchema.isBlank() && userId > 0 && contactId > 0) {
                    kafkaProducer.publishJson(COMMON_TOPIC,
                            WhatsAppContactRegisteredEvent.builder()
                                    .eventType("WHATSAPP_CONTACT_REGISTERED")
                                    .tenantSchema(tenantSchema)
                                    .userId(userId)
                                    .contactId(contactId)
                                    .build());
                    success++;
                }
            } catch (Exception e) {
                failed++;
                log.error("[Router/STAFF_SYNC] Failed to onboard operator: {}", e.getMessage(), e);
            }
        }
        log.info("[Router/STAFF_SYNC] Onboarding complete — success={} failed={} tenantSchema={}",
                success, failed, tenantSchema);
        if (failed > 0) {
            throw new IllegalStateException(
                    "[Router/STAFF_SYNC] " + failed + " operator onboarding(s) failed"
                    + " (success=" + success + ", tenantSchema=" + tenantSchema + ")");
        }
    }

    private void handleUpdateUserLanguage(JsonNode root) {
        String tenantCode = root.path("tenantCode").asText("").toLowerCase();
        int glificLanguageId = root.path("glificLanguageId").asInt(0);
        JsonNode phonesNode = root.path("pumpOperatorPhones");

        if (tenantCode.isBlank() || !tenantCode.matches("[a-z0-9_]+")) {
            log.warn("[Router/UPDATE_LANGUAGE] Invalid or missing tenantCode, skipping");
            return;
        }
        if (glificLanguageId <= 0) {
            log.warn("[Router/UPDATE_LANGUAGE] Missing glificLanguageId, skipping");
            return;
        }
        if (!phonesNode.isArray() || phonesNode.isEmpty()) {
            log.warn("[Router/UPDATE_LANGUAGE] pumpOperatorPhones is empty, skipping");
            return;
        }

        String tenantSchema = "tenant_" + tenantCode;
        int success = 0, failed = 0;
        for (JsonNode phoneNode : phonesNode) {
            String phone = phoneNode.asText("");
            if (phone.isBlank()) {
                log.warn("[Router/UPDATE_LANGUAGE] Blank or null phone entry in pumpOperatorPhones (node={}), skipping", phoneNode);
                failed++;
                continue;
            }
            try {
                Long contactId = fetchWhatsappConnectionId(tenantSchema, phone);
                if (contactId == null || contactId <= 0) {
                    log.warn("[Router/UPDATE_LANGUAGE] No whatsapp_connection_id found in schema={}", tenantSchema);
                    log.debug("[Router/UPDATE_LANGUAGE] No whatsapp_connection_id for phone={} in schema={}", phone, tenantSchema);
                    failed++;
                    continue;
                }
                glificWhatsAppService.updateContactLanguage(contactId, glificLanguageId);
                success++;
            } catch (Exception e) {
                failed++;
                log.error("[Router/UPDATE_LANGUAGE] Failed to update language: {}", e.getMessage(), e);
            }
        }
        log.info("[Router/UPDATE_LANGUAGE] complete — success={} failed={} schema={}", success, failed, tenantSchema);
        if (failed > 0) {
            // Intentionally throw to trigger Kafka retry of the whole batch.
            // updateContactLanguage is idempotent (re-setting the same language ID on a
            // contact is harmless), so retrying already-succeeded phones is safe.
            // Contrast with handleSendWelcomeMessage, where retrying would re-send a
            // one-time onboarding message — that is why the DLT pattern is used there instead.
            throw new IllegalStateException(
                    "[Router/UPDATE_LANGUAGE] " + failed + " update(s) failed (success=" + success + ")");
        }
    }

    private void handleSendWelcomeMessage(JsonNode root) {
        String tenantCode = root.path("tenantCode").asText("").toLowerCase();
        JsonNode phonesNode = root.path("pumpOperatorPhones");
        int tenantId = root.path("tenantId").asInt(0);

        if (tenantCode.isBlank() || !tenantCode.matches("[a-z0-9_]+")) {
            log.warn("[Router/WELCOME] Invalid or missing tenantCode, skipping");
            return;
        }
        if (!phonesNode.isArray() || phonesNode.isEmpty()) {
            log.warn("[Router/WELCOME] pumpOperatorPhones is empty, skipping");
            return;
        }

        String tenantSchema = "tenant_" + tenantCode;
        String welcomeFlowId = resolveWelcomeFlowId(tenantId);
        if (welcomeFlowId.isBlank()) {
            log.warn("[Router/WELCOME] No tenant-specific welcome flow ID found; using default config (tenantCode={}, tenantId={})",
                    tenantCode, tenantId);
        }
        String stateName = messageTemplateService.findStateName(tenantId);
        int success = 0, failed = 0;
        for (JsonNode phoneNode : phonesNode) {
            String phone = phoneNode.asText("");
            if (phone.isBlank()) {
                log.warn("[Router/WELCOME] Blank or null phone entry in pumpOperatorPhones (node={}), skipping", phoneNode);
                publishWelcomeDlt(tenantSchema, phoneNode.toString(), "blank_phone");
                failed++;
                continue;
            }
            try {
                UserContactInfo info = fetchUserContactInfo(tenantSchema, phone);
                if (info.contactId() == null || info.contactId() <= 0) {
                    log.warn("[Router/WELCOME] No whatsapp_connection_id found in schema={}", tenantSchema);
                    log.debug("[Router/WELCOME] No whatsapp_connection_id for phone={} in schema={}", phone, tenantSchema);
                    publishWelcomeDlt(tenantSchema, phone, "no_whatsapp_connection_id");
                    failed++;
                    continue;
                }
                if (welcomeFlowId.isBlank()) {
                    glificWhatsAppService.startWelcomeFlow(info.contactId(), info.name(), stateName);
                } else {
                    glificWhatsAppService.startWelcomeFlow(info.contactId(), welcomeFlowId, info.name(), stateName);
                }
                success++;
            } catch (Exception e) {
                log.error("[Router/WELCOME] Failed to send welcome message: {}", e.getMessage(), e);
                publishWelcomeDlt(tenantSchema, phone, e.getMessage());
                failed++;
            }
        }
        log.info("[Router/WELCOME] complete — success={} failed={} schema={}", success, failed, tenantSchema);
    }

    private void handleSendWelcomeMessageAdmin(JsonNode root) {
        String tenantCode = root.path("tenantCode").asText("");
        JsonNode phonesNode = root.path("pumpOperatorPhones");
        int tenantId = root.path("tenantId").asInt(0);

        if (tenantCode.isBlank() || !tenantCode.matches("[A-Za-z0-9_]+")) {
            log.warn("[Router/WELCOME_ADMIN] Invalid or missing tenantCode, skipping");
            return;
        }
        if (!phonesNode.isArray() || phonesNode.isEmpty()) {
            log.warn("[Router/WELCOME_ADMIN] pumpOperatorPhones is empty, skipping");
            return;
        }

        String tenantSchema = "tenant_" + tenantCode.toLowerCase();
        String welcomeFlowId = resolveWelcomeFlowId(tenantId);
        if (welcomeFlowId.isBlank()) {
            log.warn("[Router/WELCOME_ADMIN] No tenant-specific welcome flow ID found; using default config (tenantCode={}, tenantId={})",
                    tenantCode, tenantId);
        }
        String stateName = messageTemplateService.findStateName(tenantId);
        int success = 0, failed = 0;
        for (JsonNode phoneNode : phonesNode) {
            String phone = phoneNode.asText("");
            String normalized = normalizeIndianPhone(phone);
            if (normalized.isBlank()) {
                log.warn("[Router/WELCOME_ADMIN] Blank or invalid phone entry in pumpOperatorPhones (node={}), skipping", phoneNode);
                publishWelcomeDlt(tenantSchema, phoneNode.toString(), "blank_phone");
                failed++;
                continue;
            }
            try {
                UserContactInfo info = fetchUserContactInfo(tenantSchema, phone);
                if ((info.contactId() == null || info.contactId() <= 0) && !normalized.equals(phone)) {
                    info = fetchUserContactInfo(tenantSchema, normalized);
                }
                Long contactId = info.contactId();
                String name = info.name();
                if (contactId == null || contactId <= 0) {
                    contactId = glificWhatsAppService.optIn(normalized);
                    if (contactId == null || contactId <= 0) {
                        publishWelcomeDlt(tenantSchema, normalized, "optin_failed");
                        failed++;
                        continue;
                    }
                }
                if (welcomeFlowId.isBlank()) {
                    glificWhatsAppService.startWelcomeFlow(contactId, name, stateName);
                } else {
                    glificWhatsAppService.startWelcomeFlow(contactId, welcomeFlowId, name, stateName);
                }
                success++;
            } catch (Exception e) {
                log.error("[Router/WELCOME_ADMIN] Failed to send welcome message: {}", e.getMessage(), e);
                publishWelcomeDlt(tenantSchema, normalized, e.getMessage());
                failed++;
            }
        }
        log.info("[Router/WELCOME_ADMIN] complete — success={} failed={} schema={}", success, failed, tenantSchema);
    }

    private String normalizeIndianPhone(String phone) {
        if (phone == null) {
            return "";
        }
        String trimmed = phone.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("91") && trimmed.length() == 12) {
            return trimmed;
        }
        if (trimmed.length() == 10 && trimmed.chars().allMatch(Character::isDigit)) {
            return "91" + trimmed;
        }
        return trimmed;
    }

    private String resolveWelcomeFlowId(int tenantId) {
        if (tenantId <= 0) {
            return "";
        }
        return messageTemplateService.findWelcomeFlowId(tenantId).orElse("");
    }

    private void publishWelcomeDlt(String tenantSchema, String phone, String errorMessage) {
        // Derive a stable dedupe key from the business identity so that if this
        // method is called again for the same phone (e.g. Kafka consumer retry),
        // downstream processors receive a record with the same retryId and can
        // safely deduplicate. UUID.nameUUIDFromBytes produces a deterministic
        // UUID v3 for a given input; no upstream eventId is available in this payload.
        String retryId = UUID.nameUUIDFromBytes(
                ("SEND_WELCOME_MESSAGE_RETRY:" + tenantSchema + ":" + phone)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("retryId", retryId);
        payload.put("eventType", "SEND_WELCOME_MESSAGE_RETRY");
        payload.put("tenantSchema", tenantSchema);
        payload.put("failedAt", Instant.now().toString());
        payload.put("errorMessage", errorMessage);
        // phone is PII — included so downstream can reprocess, but must not surface in INFO logs
        payload.put("phone", phone);
        log.debug("[Router/WELCOME] Publishing to DLT for schema={}", tenantSchema);
        kafkaProducer.publishJson(WELCOME_DLT_TOPIC, payload);
    }

    private void handleSendLoginOtp(JsonNode root) {
        String otp = root.path("OTP").asText("");
        String deliveryChannel = root.path("deliveryChannel").asText("").trim().toUpperCase(Locale.ROOT);

        if (otp.isBlank()) {
            log.warn("[Router/SEND_LOGIN_OTP] OTP is missing, skipping");
            return;
        }

        if (deliveryChannel.isBlank()) {
            log.error("[Router/SEND_LOGIN_OTP] deliveryChannel is missing or blank, skipping");
            return;
        }

        if ("SMS".equals(deliveryChannel)) {
            String phone = root.path("officerPhoneNumber").asText("").strip();
            int expiryMinutes = root.path("expiryMinutes").asInt(5);

            if (phone.isBlank()) {
                log.warn("[Router/SEND_LOGIN_OTP/SMS] officerPhoneNumber is missing, skipping");
                return;
            }

            // Validate expiryMinutes and default to 5 if invalid
            if (expiryMinutes <= 0) {
                log.warn("[Router/SEND_LOGIN_OTP/SMS] invalid expiryMinutes={}, defaulting to 5", expiryMinutes);
                expiryMinutes = 5;
            }

            // Use reactive flow to avoid blocking the Kafka listener thread
            smsCountryService.sendOtpReactive(phone, otp, expiryMinutes)
                    .doOnNext(sent -> {
                        if (sent) {
                            log.info("[Router/SEND_LOGIN_OTP/SMS] → SENT");
                            log.debug("[Router/SEND_LOGIN_OTP/SMS] phone={} → SENT", phone);
                        } else {
                            // Non-retryable failure (4xx API rejection) — log as warning, do not throw
                            log.warn("[Router/SEND_LOGIN_OTP/SMS] SMS OTP delivery rejected by provider (non-retryable)");
                            log.debug("[Router/SEND_LOGIN_OTP/SMS] phone={} → REJECTED", phone);
                        }
                    })
                    .doOnError(e -> {
                        // Retryable failure (5xx, network issue) — log error; exception will propagate to Kafka container
                        log.error("[Router/SEND_LOGIN_OTP/SMS] SMS OTP delivery failed (retryable): {}", e.getMessage());
                        log.debug("[Router/SEND_LOGIN_OTP/SMS] phone={} → ERROR: {}", phone, e.getMessage());
                    })
                    .onErrorResume(e -> {
                        // Swallow retryable errors to prevent Kafka retries of the entire event
                        // (OTP sends are time-sensitive; a delayed retry would deliver an expired OTP)
                        log.warn("[Router/SEND_LOGIN_OTP/SMS] Suppressing retryable error to prevent Kafka retry");
                        return Mono.empty();
                    })
                    .subscribe();
        } else if ("WHATSAPP".equals(deliveryChannel)) {
            String phone = root.path("officerPhoneNumber").asText("").strip();
            JsonNode glificIdNode = root.path("glific_id");
            long contactId = glificIdNode.asLong(0);

            if (contactId > 0) {
                // glific_id was provided and valid
            } else if (!phone.isBlank()) {
                log.info("[Router/SEND_LOGIN_OTP/WHATSAPP] glific_id not provided, opting in via phone");
                contactId = glificWhatsAppService.optIn(phone);
                if (contactId <= 0) {
                    log.warn("[Router/SEND_LOGIN_OTP/WHATSAPP] optIn returned invalid contactId {}, skipping", contactId);
                    return;
                }
            } else {
                log.warn("[Router/SEND_LOGIN_OTP/WHATSAPP] Neither glific_id nor officerPhoneNumber provided, skipping");
                return;
            }

            boolean sent = whatsAppChannel.sendLoginOtp(contactId, otp);
            if (!sent) {
                throw new IllegalStateException("[Router/SEND_LOGIN_OTP/WHATSAPP] WhatsApp login OTP delivery failed");
            }
            log.info("[Router/SEND_LOGIN_OTP/WHATSAPP] → SENT contactId={}", contactId);
        } else {
            log.error("[Router/SEND_LOGIN_OTP] Unsupported deliveryChannel '{}', must be 'SMS' or 'WHATSAPP', skipping", deliveryChannel);
        }
    }

    private void handleInviteEmail(JsonNode root) {
        InviteEmailEvent event;
        try {
            event = objectMapper.treeToValue(root, InviteEmailEvent.class);
        } catch (Exception e) {
            log.error("[Router/INVITE_EMAIL] Malformed event, routing to DLT: {}", e.getMessage());
            publishEmailDlt("SEND_INVITE_EMAIL", null, "malformed_event: " + e.getMessage());
            return;
        }
        if (event.getTo() == null || event.getTo().isBlank()) {
            log.warn("[Router/INVITE_EMAIL] Missing 'to' field, routing to DLT");
            publishEmailDlt("SEND_INVITE_EMAIL", null, "missing_to");
            return;
        }
        if (event.getInviteLink() == null || event.getInviteLink().isBlank()) {
            log.warn("[Router/INVITE_EMAIL] Missing 'inviteLink' field, routing to DLT");
            publishEmailDlt("SEND_INVITE_EMAIL", event.getTo(), "missing_invite_link");
            return;
        }
        try {
            if ("STATE_ADMIN".equalsIgnoreCase(event.getRole())
                    && event.getStateName() != null && !event.getStateName().isBlank()) {
                accountEmailService.sendStateAdminInviteEmail(
                        event.getTo(), event.getName(), event.getStateName(),
                        event.getInviteLink(), event.getExpiryHours());
            } else {
                accountEmailService.sendInviteEmail(
                        event.getTo(), event.getName(), event.getRole(),
                        event.getInviteLink(), event.getExpiryHours());
            }
            log.info("[Router/INVITE_EMAIL] Invite email dispatched recipientRole={}", event.getRole());
        } catch (Exception e) {
            log.error("[Router/INVITE_EMAIL] Email delivery failure, routing to DLT: {}", e.getMessage());
            publishEmailDlt("SEND_INVITE_EMAIL", event.getTo(), "email_delivery_error");
        }
    }

    private void handleReinviteEmail(JsonNode root) {
        InviteEmailEvent event;
        try {
            event = objectMapper.treeToValue(root, InviteEmailEvent.class);
        } catch (Exception e) {
            log.error("[Router/REINVITE_EMAIL] Malformed event, routing to DLT: {}", e.getMessage());
            publishEmailDlt("SEND_REINVITE_EMAIL", null, "malformed_event: " + e.getMessage());
            return;
        }
        if (event.getTo() == null || event.getTo().isBlank()) {
            log.warn("[Router/REINVITE_EMAIL] Missing 'to' field, routing to DLT");
            publishEmailDlt("SEND_REINVITE_EMAIL", null, "missing_to");
            return;
        }
        if (event.getInviteLink() == null || event.getInviteLink().isBlank()) {
            log.warn("[Router/REINVITE_EMAIL] Missing 'inviteLink' field, routing to DLT");
            publishEmailDlt("SEND_REINVITE_EMAIL", event.getTo(), "missing_invite_link");
            return;
        }
        try {
            accountEmailService.sendReinviteEmail(event.getTo(), event.getName(), event.getInviteLink(), event.getExpiryHours());
            log.info("[Router/REINVITE_EMAIL] Reinvite email dispatched recipientRole={}", event.getRole());
        } catch (Exception e) {
            log.error("[Router/REINVITE_EMAIL] Email delivery failure, routing to DLT: {}", e.getMessage());
            publishEmailDlt("SEND_REINVITE_EMAIL", event.getTo(), "email_delivery_error");
        }
    }

    private void handlePasswordResetEmail(JsonNode root) {
        ResetPasswordEmailEvent event;
        try {
            event = objectMapper.treeToValue(root, ResetPasswordEmailEvent.class);
        } catch (Exception e) {
            log.error("[Router/PASSWORD_RESET_EMAIL] Malformed event, routing to DLT: {}", e.getMessage());
            publishEmailDlt("SEND_PASSWORD_RESET_EMAIL", null, "malformed_event: " + e.getMessage());
            return;
        }
        if (event.getTo() == null || event.getTo().isBlank()) {
            log.warn("[Router/PASSWORD_RESET_EMAIL] Missing 'to' field, routing to DLT");
            publishEmailDlt("SEND_PASSWORD_RESET_EMAIL", null, "missing_to");
            return;
        }
        if (event.getResetLink() == null || event.getResetLink().isBlank()) {
            log.warn("[Router/PASSWORD_RESET_EMAIL] Missing 'resetLink' field, routing to DLT");
            publishEmailDlt("SEND_PASSWORD_RESET_EMAIL", event.getTo(), "missing_reset_link");
            return;
        }
        try {
            accountEmailService.sendPasswordResetEmail(event.getTo(), event.getResetLink(), event.getExpiryMinutes());
            log.info("[Router/PASSWORD_RESET_EMAIL] Password reset email dispatched");
        } catch (Exception e) {
            log.error("[Router/PASSWORD_RESET_EMAIL] Email delivery failure, routing to DLT: {}", e.getMessage());
            publishEmailDlt("SEND_PASSWORD_RESET_EMAIL", event.getTo(), "email_delivery_error");
        }
    }

    private void publishEmailDlt(String originalEventType, String to, String errorReason) {
        String seed = "ACCOUNT_EMAIL_FAILED:" + originalEventType + ":"
                + (to != null ? to : "unknown:" + Instant.now().toEpochMilli());
        String retryId = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("retryId", retryId);
        payload.put("eventType", "ACCOUNT_EMAIL_FAILED");
        payload.put("originalEventType", originalEventType);
        payload.put("failedAt", Instant.now().toString());
        payload.put("errorReason", errorReason);
        // to is PII — included for reprocessing, must not surface in INFO logs
        payload.put("to", to != null ? to : "unknown");
        log.debug("[Router/EMAIL_DLT] Publishing to DLT originalEventType={}", originalEventType);
        try {
            kafkaProducer.publishJson(ACCOUNT_EMAIL_DLT_TOPIC, payload);
        } catch (Exception e) {
            log.error("[Router/EMAIL_DLT] Failed to publish to DLT originalEventType={}: {}", originalEventType, e.getMessage());
            // Do not rethrow — DLT publish failure must not trigger Kafka retries on the original handler.
        }
    }

    /**
     * Looks up the Glific contact ID stored for a given phone number in the tenant's user_table.
     * tenantSchema is pre-validated to match {@code [a-z0-9_]+} before this call.
     */
    private Long fetchWhatsappConnectionId(String tenantSchema, String phone) {
        Long byHash = fetchWhatsappConnectionIdByHash(tenantSchema, phone);
        if (byHash != null) {
            return byHash;
        }
        String sql = "SELECT whatsapp_connection_id FROM " + tenantSchema
                + ".user_table WHERE phone_number = ? LIMIT 1";
        List<Long> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getObject("whatsapp_connection_id", Long.class), phone);
        return rows.isEmpty() ? null : rows.get(0);
    }

    record UserContactInfo(Long contactId, String name) {}

    /**
     * Looks up both the Glific contact ID and display name for a phone number.
     * tenantSchema is pre-validated to match {@code [a-z0-9_]+} before this call.
     */
    private UserContactInfo fetchUserContactInfo(String tenantSchema, String phone) {
        UserContactInfo byHash = fetchUserContactInfoByHash(tenantSchema, phone);
        if (byHash.contactId() != null || byHash.name() != null) {
            return byHash;
        }
        String sql = "SELECT whatsapp_connection_id, title FROM " + tenantSchema
                + ".user_table WHERE phone_number = ? LIMIT 1";
        List<UserContactInfo> rows = jdbcTemplate.query(sql,
                (rs, n) -> new UserContactInfo(
                        rs.getObject("whatsapp_connection_id", Long.class),
                        piiEncryptionService.safeDecrypt(rs.getString("title"))),
                phone);
        return rows.isEmpty() ? new UserContactInfo(null, null) : rows.get(0);
    }

    private Long fetchWhatsappConnectionIdByHash(String tenantSchema, String phone) {
        String sql = "SELECT whatsapp_connection_id FROM " + tenantSchema
                + ".user_table WHERE phone_number_hash = ? LIMIT 1";
        try {
            for (String candidate : buildPhoneCandidates(phone)) {
                String lookupHash = piiEncryptionService.hmac(candidate);
                List<Long> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getObject("whatsapp_connection_id", Long.class), lookupHash);
                if (rows != null && !rows.isEmpty()) {
                    return rows.get(0);
                }
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    private UserContactInfo fetchUserContactInfoByHash(String tenantSchema, String phone) {
        String sql = "SELECT whatsapp_connection_id, title FROM " + tenantSchema
                + ".user_table WHERE phone_number_hash = ? LIMIT 1";
        try {
            for (String candidate : buildPhoneCandidates(phone)) {
                String lookupHash = piiEncryptionService.hmac(candidate);
                List<UserContactInfo> rows = jdbcTemplate.query(sql,
                        (rs, n) -> new UserContactInfo(
                                rs.getObject("whatsapp_connection_id", Long.class),
                                piiEncryptionService.safeDecrypt(rs.getString("title"))),
                        lookupHash);
                if (rows != null && !rows.isEmpty()) {
                    return rows.get(0);
                }
            }
        } catch (Exception ex) {
            return new UserContactInfo(null, null);
        }
        return new UserContactInfo(null, null);
    }

    private List<String> buildPhoneCandidates(String phone) {
        if (phone == null || phone.isBlank()) {
            return List.of();
        }
        String raw = phone.trim();
        String digits = raw.replaceAll("\\D", "");
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(raw);
        if (!digits.isBlank()) {
            candidates.add(digits);
            if (digits.length() == 10) {
                candidates.add("91" + digits);
            } else if (digits.length() == 12 && digits.startsWith("91")) {
                candidates.add(digits.substring(2));
            }
        }
        return new ArrayList<>(candidates);
    }

    private void handleEscalation(JsonNode root) throws Exception {
        String officerPhone = root.path("officerPhone").asText("");
        String officerName = root.path("officerName").asText("Officer");
        String officerUserType = root.path("officerUserType").asText("");
        int level = root.path("escalationLevel").asInt(1);
        int tenantId = root.path("tenantId").asInt(0);
        int officerLanguageId = root.path("officerLanguageId").asInt(0);
        String tenantSchema = root.path("tenantSchema").asText("");
        long officerId = root.path("officerId").asLong(0);
        long storedId = root.path("officerWhatsappConnectionId").asLong(0);
        String correlationId = root.path("correlationId").asText("");

        if (storedId <= 0 && officerPhone.isBlank()) {
            log.warn("[Router/ESCALATION] officerPhone and officerWhatsappConnectionId are both missing, skipping");
            return;
        }

        JsonNode operatorsNode = root.path("operators");
        List<OperatorEscalationDetail> operators = new ArrayList<>();
        if (operatorsNode.isArray()) {
            for (JsonNode node : operatorsNode) {
                operators.add(objectMapper.treeToValue(node, OperatorEscalationDetail.class));
            }
        }

        if (operators.isEmpty()) {
            log.warn("[Router/ESCALATION] No operators in event, skipping");
            return;
        }

        String filename = escalationPdfService.generate(operators, level, officerName, officerUserType, correlationId);
        java.nio.file.Path localPath = Paths.get(reportDir, filename);
        String minioUrl;
        try {
            minioUrl = minioStorageService.upload(localPath);
        } catch (Exception uploadEx) {
            log.error("[Router/ESCALATION] MinIO upload failed, retaining local PDF for recovery: {} — {}",
                    localPath, uploadEx.getMessage());
            throw uploadEx;
        }
        try {
            Files.deleteIfExists(localPath);
        } catch (Exception cleanupEx) {
            log.warn("[Router/ESCALATION] Could not delete local PDF {}: {}",
                    localPath, cleanupEx.getMessage());
        }

        long contactId;
        if (storedId > 0) {
            contactId = storedId;
        } else {
            contactId = glificWhatsAppService.optIn(officerPhone);
            if (!tenantSchema.isBlank() && officerId > 0 && contactId > 0) {
                kafkaProducer.publishJson(COMMON_TOPIC,
                        WhatsAppContactRegisteredEvent.builder()
                                .eventType("WHATSAPP_CONTACT_REGISTERED")
                                .tenantSchema(tenantSchema)
                                .userId(officerId)
                                .contactId(contactId)
                                .build());
            }
        }

        boolean sent = whatsAppChannel.sendDocument(contactId, minioUrl);
        if (!sent) {
            throw new IllegalStateException("[Router/ESCALATION] WhatsApp escalation delivery failed");
        }
        String loggableUrl = minioUrl.replaceFirst("\\?.*$", "");
        log.info("[Router/ESCALATION] level={} → {} ({})", level, sent ? "SENT" : "FAILED", loggableUrl);
        log.debug("[Router/ESCALATION] officer={} level={} → {} ({})", officerPhone, level,
                sent ? "SENT" : "FAILED", loggableUrl);
    }

    /**
     * Handles a {@code DAILY_REPORT_KPIS} event: resolves the officer's contact from the operational
     * {@code user_table} (analytics never sees PII), renders the report PDF, uploads it to MinIO, and
     * sends the document HSM via Glific. Mirrors {@link #handleEscalation}.
     */
    private void handleDailySituationReport(JsonNode root) throws Exception {
        int tenantId = root.path("tenantId").asInt(0);
        String tenantSchema = root.path("tenantSchema").asText("");
        long officerUserId = root.path("officerUserId").asLong(0);
        String officerUserType = root.path("officerUserType").asText("");
        String corr = root.path("correlationId").asText("");
        long startNanos = System.nanoTime();

        if (tenantSchema.isBlank() || !tenantSchema.matches(SCHEMA_PATTERN) || officerUserId <= 0) {
            log.warn("[Router/DAILY_REPORT] corr={} invalid tenantSchema/officerUserId, skipping", corr);
            return;
        }
        if (!root.hasNonNull("kpis")) {
            log.warn("[Router/DAILY_REPORT] corr={} missing kpis payload for officer={}, skipping", corr, officerUserId);
            return;
        }

        log.info("[Router/DAILY_REPORT] corr={} received: tenant={} officer={} role={}",
                corr, tenantId, officerUserId, officerUserType);

        // SDO layout is not yet specified — compute-and-log but do not deliver until enabled.
        if ("SUB_DIVISIONAL_OFFICER".equalsIgnoreCase(officerUserType) && !dailyReportSdoEnabled) {
            log.info("[Router/DAILY_REPORT] corr={} SDO delivery disabled (no SDO layout yet); skipping officer={}",
                    corr, officerUserId);
            return;
        }

        DailyReportKpis kpis = objectMapper.treeToValue(root.path("kpis"), DailyReportKpis.class);
        if (!isRenderableKpis(kpis)) {
            log.warn("[Router/DAILY_REPORT] corr={} incomplete/malformed kpis for officer={}, skipping (non-retryable)",
                    corr, officerUserId);
            return;
        }

        OfficerContact officer = resolveOfficerContactById(tenantSchema, officerUserId);
        if (officer.contactId() == null && (officer.phone() == null || officer.phone().isBlank())) {
            log.warn("[Router/DAILY_REPORT] corr={} no phone or whatsapp_connection_id for officer={} in schema={}, skipping",
                    corr, officerUserId, tenantSchema);
            return;
        }

        String officerName = officer.name() != null ? officer.name() : "Officer";
        log.debug("[Router/DAILY_REPORT] corr={} resolved officer={} name='{}' hasContactId={}",
                corr, officerUserId, officerName, officer.contactId() != null);

        List<DailyReportPriorityRow> priorityRows = buildPriorityRows(tenantSchema, kpis, corr);

        String filename = dailyReportPdfService.generate(kpis, officerName, officerUserType, priorityRows);
        java.nio.file.Path localPath = Paths.get(reportDir, filename);
        String minioUrl;
        try {
            minioUrl = minioStorageService.upload(localPath);
        } catch (Exception uploadEx) {
            log.error("[Router/DAILY_REPORT] corr={} MinIO upload failed, retaining local PDF for recovery: {} — {}",
                    corr, localPath, uploadEx.getMessage());
            throw uploadEx;
        }
        try {
            Files.deleteIfExists(localPath);
        } catch (Exception cleanupEx) {
            log.warn("[Router/DAILY_REPORT] corr={} could not delete local PDF {}: {}", corr, localPath, cleanupEx.getMessage());
        }

        long contactId = resolveContactIdOrOptIn(officer, tenantSchema, officerUserId);

        boolean sent = whatsAppChannel.sendDailyReport(contactId, minioUrl, officerUserType);
        if (!sent) {
            throw new IllegalStateException("[Router/DAILY_REPORT] corr=" + corr + " WhatsApp daily report delivery failed");
        }
        String loggableUrl = minioUrl.replaceFirst("\\?.*$", "");
        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("[Router/DAILY_REPORT] corr={} SENT: tenant={} officer={} role={} priorityRows={} tookMs={} ({})",
                corr, tenantId, officerUserId, officerUserType, priorityRows.size(), tookMs, loggableUrl);
    }

    /**
     * Resolves each analytics Priority Action (schemeId + issue + daysNoSupply) into a printable row
     * by looking up the scheme's name + IMIS id and its pump operators (Jal Mitras) from the
     * operational schema. Schemes that can't be resolved are still shown with the ids we have.
     */
    private List<DailyReportPriorityRow> buildPriorityRows(String tenantSchema, DailyReportKpis kpis, String corr) {
        List<DailyReportPriorityRow> rows = new ArrayList<>();
        if (kpis.getPriorityActions() == null) {
            return rows;
        }
        for (DailyReportKpis.PriorityAction pa : kpis.getPriorityActions()) {
            SchemeLabel scheme = resolveSchemeLabel(tenantSchema, pa.getSchemeId());
            List<OperatorContact> operators = resolvePumpOperators(tenantSchema, pa.getSchemeId());
            String names = operators.stream().map(OperatorContact::name)
                    .filter(n -> n != null && !n.isBlank()).collect(java.util.stream.Collectors.joining(", "));
            String mobiles = operators.stream().map(OperatorContact::phone)
                    .filter(p -> p != null && !p.isBlank()).collect(java.util.stream.Collectors.joining(", "));
            rows.add(DailyReportPriorityRow.builder()
                    .scheme(scheme.schemeName() != null ? scheme.schemeName() : ("#" + pa.getSchemeId()))
                    .imisId(scheme.centreSchemeId() != null ? scheme.centreSchemeId() : "")
                    .jalMitraNames(names)
                    .jalMitraMobiles(mobiles)
                    .issue(pa.getIssue() != null ? pa.getIssue() : "")
                    .remarks(formatNoSupplyRemark(pa.getDaysNoSupply()))
                    .build());
        }
        log.debug("[Router/DAILY_REPORT] corr={} built {} priority row(s)", corr, rows.size());
        return rows;
    }

    private String formatNoSupplyRemark(Integer daysNoSupply) {
        if (daysNoSupply == null) {
            return "No recorded water supply";
        }
        if (daysNoSupply <= 0) {
            return "No water supply today";
        }
        return "No water supply for past " + daysNoSupply + (daysNoSupply == 1 ? " day" : " days");
    }

    /**
     * A KPI payload is renderable only when both dates are present and ISO-parseable and both
     * day-KPI blocks exist. Guarding here keeps a malformed/incomplete payload from surfacing as a
     * {@link DateTimeParseException} or NPE inside PDF rendering, which the container would retry;
     * instead it is treated as a permanent, non-retryable skip like the other checks above.
     */
    private boolean isRenderableKpis(DailyReportKpis kpis) {
        if (kpis == null || kpis.getYesterday() == null || kpis.getPreviousDay() == null) {
            return false;
        }
        return isIsoDate(kpis.getReportDate()) && isIsoDate(kpis.getPreviousDate());
    }

    private boolean isIsoDate(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Officer contact resolved from the operational {@code user_table} by user id. */
    private record OfficerContact(Long contactId, String name, String phone) {}

    /**
     * Returns the officer's stored Glific contact id, or opts them in by phone and publishes a
     * {@code WHATSAPP_CONTACT_REGISTERED} event so tenant-service persists the new contact id.
     */
    private long resolveContactIdOrOptIn(OfficerContact officer, String tenantSchema, long officerUserId) {
        if (officer.contactId() != null && officer.contactId() > 0) {
            return officer.contactId();
        }
        long contactId = glificWhatsAppService.optIn(officer.phone());
        if (contactId > 0) {
            kafkaProducer.publishJson(COMMON_TOPIC,
                    WhatsAppContactRegisteredEvent.builder()
                            .eventType("WHATSAPP_CONTACT_REGISTERED")
                            .tenantSchema(tenantSchema)
                            .userId(officerUserId)
                            .contactId(contactId)
                            .build());
        }
        return contactId;
    }

    /**
     * Resolves an officer's Glific contact id, decrypted display name, and decrypted phone number
     * from {@code <tenantSchema>.user_table} by user id. {@code tenantSchema} is validated by the
     * caller against {@link #SCHEMA_PATTERN} before interpolation (schema names are SQL identifiers
     * and cannot be bound as {@code ?}); the user id is bound as a parameter.
     */
    @SuppressWarnings("java:S2077")
    private OfficerContact resolveOfficerContactById(String tenantSchema, long userId) {
        String sql = "SELECT whatsapp_connection_id, title, phone_number FROM " + tenantSchema
                + ".user_table WHERE id = ? LIMIT 1";
        List<OfficerContact> rows = jdbcTemplate.query(sql,
                (rs, n) -> new OfficerContact(
                        rs.getObject("whatsapp_connection_id", Long.class),
                        piiEncryptionService.safeDecrypt(rs.getString("title")),
                        piiEncryptionService.safeDecrypt(rs.getString("phone_number"))),
                userId);
        return rows.isEmpty() ? new OfficerContact(null, null, null) : rows.get(0);
    }

    /** Scheme display fields resolved from the operational {@code scheme_master_table} by id. */
    private record SchemeLabel(String schemeName, String centreSchemeId) {}

    /** One pump operator (Jal Mitra) with decrypted name + phone. */
    private record OperatorContact(String name, String phone) {}

    /**
     * Resolves a scheme's display name + IMIS id (centre_scheme_id) from the operational schema.
     * {@code tenantSchema} is validated against {@link #SCHEMA_PATTERN} by the caller; {@code schemeId}
     * binds as {@code ?}.
     */
    @SuppressWarnings("java:S2077")
    private SchemeLabel resolveSchemeLabel(String tenantSchema, int schemeId) {
        String sql = "SELECT scheme_name, centre_scheme_id FROM " + tenantSchema
                + ".scheme_master_table WHERE id = ? AND deleted_at IS NULL LIMIT 1";
        List<SchemeLabel> rows = jdbcTemplate.query(sql,
                (rs, n) -> new SchemeLabel(rs.getString("scheme_name"), rs.getString("centre_scheme_id")),
                schemeId);
        return rows.isEmpty() ? new SchemeLabel(null, null) : rows.get(0);
    }

    /**
     * Resolves all active PUMP_OPERATOR (Jal Mitra) users mapped to a scheme, with decrypted name +
     * phone. {@code tenantSchema} is validated by the caller; {@code schemeId} binds as {@code ?}.
     */
    @SuppressWarnings("java:S2077")
    private List<OperatorContact> resolvePumpOperators(String tenantSchema, int schemeId) {
        String sql = "SELECT u.title, u.phone_number FROM " + tenantSchema + ".user_scheme_mapping_table usm "
                + "JOIN " + tenantSchema + ".user_table u ON u.id = usm.user_id "
                + "JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type "
                + "WHERE usm.scheme_id = ? AND UPPER(ut.c_name) = 'PUMP_OPERATOR' "
                + "AND usm.status = 1 AND u.status = 1 AND usm.deleted_at IS NULL AND u.deleted_at IS NULL "
                + "ORDER BY u.id";
        return jdbcTemplate.query(sql,
                (rs, n) -> new OperatorContact(
                        piiEncryptionService.safeDecrypt(rs.getString("title")),
                        piiEncryptionService.safeDecrypt(rs.getString("phone_number"))),
                schemeId);
    }
}
