package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.channel.provider.ReportSendOutcome;
import org.arghyam.jalsoochak.message.channel.provider.SmsSender;
import org.arghyam.jalsoochak.message.channel.provider.TenantChannelProviders;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendResult;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendStage;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.channel.WhatsAppChannel;
import org.arghyam.jalsoochak.message.dto.OperatorEscalationDetail;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
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
    private final WhatsAppSender whatsAppSender;
    private final TenantChannelProviders channelProviders;
    private final KafkaProducer kafkaProducer;
    private final EscalationPdfService escalationPdfService;
    private final DailyReportPdfService dailyReportPdfService;
    private final WeeklyReportPdfService weeklyReportPdfService;
    private final MinioStorageService minioStorageService;
    private final MessageTemplateService messageTemplateService;
    private final AccountEmailService accountEmailService;
    private final JdbcTemplate jdbcTemplate;
    private final PiiEncryptionService piiEncryptionService;
    private final TenantRefResolver tenantRefResolver;

    @Value("${escalation.report.dir:/tmp/escalation-reports/}")
    private String reportDir;

    @Value("${app.base-url:http://localhost:8085}")
    private String baseUrl;

    private static final String SCHEMA_PATTERN = "^[a-z0-9_]+$";

    /** The presigned query string on a MinIO URL — stripped before any URL is logged. */
    private static final String URL_QUERY_SUFFIX = "\\?.*$";

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
            case "WEEKLY_REPORT_KPIS" -> handleWeeklySituationReport(root);
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
            contactId = whatsAppSender.optIn(phone);
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
                whatsAppSender.updateContactLanguage(contactId, glificLanguageId);
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
                    whatsAppSender.startWelcomeFlow(info.contactId(), info.name(), stateName);
                } else {
                    whatsAppSender.startWelcomeFlow(info.contactId(), welcomeFlowId, info.name(), stateName);
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
                    contactId = whatsAppSender.optIn(normalized);
                    if (contactId == null || contactId <= 0) {
                        publishWelcomeDlt(tenantSchema, normalized, "optin_failed");
                        failed++;
                        continue;
                    }
                }
                if (welcomeFlowId.isBlank()) {
                    whatsAppSender.startWelcomeFlow(contactId, name, stateName);
                } else {
                    whatsAppSender.startWelcomeFlow(contactId, welcomeFlowId, name, stateName);
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

            TenantRef tenant = resolveTenant(root);

            // PER-TENANT-PROVIDERS: the tenant's own SMSCountry account when it has configured
            // one, the system default otherwise — including while the flag is off, which is every
            // send today (O2-9). Resolved per message so a settings change takes effect without a
            // restart; the lookup is cached, so it costs nothing on the OTP path.
            SmsSender smsSender = channelProviders.smsFor(tenant);

            // Use reactive flow to avoid blocking the Kafka listener thread
            smsSender.sendOtp(phone, otp, expiryMinutes)
                    .doOnNext(sent -> {
                        if (sent) {
                            log.info("[Router/SEND_LOGIN_OTP/SMS] {} → SENT", tenant);
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
                contactId = whatsAppSender.optIn(phone);
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
        TenantRef tenant = tenantRefResolver.resolve(null, event.getTenantCode());
        try {
            if ("STATE_ADMIN".equalsIgnoreCase(event.getRole())
                    && event.getStateName() != null && !event.getStateName().isBlank()) {
                accountEmailService.sendStateAdminInviteEmail(
                        tenant, event.getTo(), event.getName(), event.getStateName(),
                        event.getInviteLink(), event.getExpiryHours());
            } else {
                accountEmailService.sendInviteEmail(
                        tenant, event.getTo(), event.getName(), event.getRole(),
                        event.getInviteLink(), event.getExpiryHours());
            }
            log.info("[Router/INVITE_EMAIL] Invite email dispatched recipientRole={} {}",
                    event.getRole(), tenant);
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
        TenantRef tenant = tenantRefResolver.resolve(null, event.getTenantCode());
        try {
            accountEmailService.sendReinviteEmail(tenant, event.getTo(), event.getName(),
                    event.getInviteLink(), event.getExpiryHours());
            log.info("[Router/REINVITE_EMAIL] Reinvite email dispatched recipientRole={} {}",
                    event.getRole(), tenant);
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
        TenantRef tenant = tenantRefResolver.resolve(event.getTenantId(), event.getTenantCode());
        try {
            accountEmailService.sendPasswordResetEmail(tenant, event.getTo(), event.getResetLink(),
                    event.getExpiryMinutes());
            log.info("[Router/PASSWORD_RESET_EMAIL] Password reset email dispatched {}", tenant);
        } catch (Exception e) {
            log.error("[Router/PASSWORD_RESET_EMAIL] Email delivery failure, routing to DLT: {}", e.getMessage());
            publishEmailDlt("SEND_PASSWORD_RESET_EMAIL", event.getTo(), "email_delivery_error");
        }
    }

    /**
     * Normalises the optional tenant fields on a raw event payload. Both are read strictly —
     * a JSON null or a value of the wrong type is treated as absent, never as the text
     * {@code "null"}.
     */
    private TenantRef resolveTenant(JsonNode root) {
        JsonNode idNode = root.path("tenantId");
        JsonNode codeNode = root.path("tenantCode");
        return tenantRefResolver.resolve(
                idNode.isIntegralNumber() ? idNode.asInt() : null,
                codeNode.isTextual() ? codeNode.asText() : null);
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
            contactId = whatsAppSender.optIn(officerPhone);
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
        String loggableUrl = loggableUrl(minioUrl);
        log.info("[Router/ESCALATION] level={} → {} ({})", level, sent ? "SENT" : "FAILED", loggableUrl);
        log.debug("[Router/ESCALATION] officer={} level={} → {} ({})", officerPhone, level,
                sent ? "SENT" : "FAILED", loggableUrl);
    }

    /**
     * Handles a {@code DAILY_REPORT_KPIS} event: resolves the officer's contact from the operational
     * {@code user_table} (analytics never sees PII), renders the report PDF, uploads it to MinIO, and
     * sends the document HSM via Glific. Mirrors {@link #handleEscalation}.
     *
     * <p>Every terminal outcome — one per officer — is logged with a {@code result=} tag and a
     * {@code role=} field so daily-report delivery can be counted per role straight from the logs.
     * {@code result=GENERATED} marks a rendered PDF and {@code result=SENT} a send Glific
     * <em>accepted</em>, so the two are counted separately. Three more tags keep the SENT count honest:
     * {@code SUPPRESSED} is a dry-run that reached no Glific mutation, {@code FAILED_DELIVERY} a
     * rejected send, and {@code DELIVERY_UNCONFIRMED} one Glific may have sent but cannot confirm —
     * the last of which is deliberately <strong>not</strong> retried (see
     * {@link #isAmbiguousDelivery}). None of them means WhatsApp delivered anything; only
     * {@link WhatsAppDeliveryReconciliationService} can say that.</p>
     */
    private void handleDailySituationReport(JsonNode root) throws Exception {
        int tenantId = root.path("tenantId").asInt(0);
        String tenantSchema = root.path("tenantSchema").asText("");
        long officerUserId = root.path("officerUserId").asLong(0);
        // Canonical role: trimmed once so the SDO gate, the PDF layout and the Glific template are all
        // chosen from the same token (the latter two trim internally, the gate below did not).
        String officerUserType = root.path("officerUserType").asText("").trim();
        String corr = root.path("correlationId").asText("");
        String role = officerUserType.isEmpty() ? "UNKNOWN" : officerUserType;
        long startNanos = System.nanoTime();

        if (tenantSchema.isBlank() || !tenantSchema.matches(SCHEMA_PATTERN) || officerUserId <= 0) {
            log.warn("[Router/DAILY_REPORT] corr={} result=SKIPPED_INVALID_EVENT role={} tenant={}",
                    corr, role, tenantId);
            return;
        }
        if (!root.hasNonNull("kpis")) {
            log.warn("[Router/DAILY_REPORT] corr={} result=SKIPPED_NO_KPIS role={} tenant={} officer={}",
                    corr, role, tenantId, officerUserId);
            return;
        }

        log.info("[Router/DAILY_REPORT] corr={} received: tenant={} officer={} role={}",
                corr, tenantId, officerUserId, role);

        // The daily report is a Section Officer product now; SDOs are served by the weekly one. This
        // still has to be checked rather than assumed: events published before the upgrade can be
        // sitting in the topic, and an SDO's queued daily report must be dropped, not rendered into a
        // layout that no longer describes their command.
        if ("SUB_DIVISIONAL_OFFICER".equalsIgnoreCase(officerUserType)) {
            log.info("[Router/DAILY_REPORT] corr={} result=SKIPPED_SDO_DAILY_RETIRED role={} tenant={} officer={}"
                            + " — SDOs receive the weekly report instead",
                    corr, role, tenantId, officerUserId);
            return;
        }

        DailyReportKpis kpis = objectMapper.treeToValue(root.path("kpis"), DailyReportKpis.class);
        if (!isRenderableKpis(kpis)) {
            log.warn("[Router/DAILY_REPORT] corr={} result=SKIPPED_MALFORMED_KPIS role={} tenant={} officer={}"
                    + " (non-retryable)", corr, role, tenantId, officerUserId);
            return;
        }

        OfficerContact officer = resolveOfficerContactById(tenantSchema, officerUserId);
        if (officer.contactId() == null && (officer.phone() == null || officer.phone().isBlank())) {
            log.warn("[Router/DAILY_REPORT] corr={} result=SKIPPED_NO_CONTACT role={} tenant={} officer={} schema={}",
                    corr, role, tenantId, officerUserId, tenantSchema);
            return;
        }

        String officerName = officer.name() != null ? officer.name() : "Officer";
        log.debug("[Router/DAILY_REPORT] corr={} resolved officer={} name='{}' hasContactId={}",
                corr, officerUserId, officerName, officer.contactId() != null);

        // Resolved before the report is built. A contact id of 0 while delivery is live means the opt-in
        // never produced a Glific contact, and nothing downstream can recover from that: sending anyway
        // comes back as "Receiver does not exist", and retrying cannot conjure a contact id while it
        // stalls the whole partition. Doing it here means the dead end costs no PDF render and no MinIO
        // upload — the previous order paid for both, then deleted the file and gave up.
        long contactId = resolveContactIdOrOptIn(officer, tenantSchema, officerUserId);
        if (contactId <= 0 && whatsAppSender.isDailyReportDeliveryEnabled()) {
            log.error("[Router/DAILY_REPORT] corr={} result=SKIPPED_NO_CONTACT_ID role={} tenant={} officer={}"
                            + " — Glific opt-in returned no contact id (non-retryable)",
                    corr, role, tenantId, officerUserId);
            return;
        }

        List<ReportSchemeRow> noSupplyRows;
        List<ReportSchemeRow> anomalyRows;
        java.nio.file.Path localPath;
        try {
            noSupplyRows = buildSchemeRows(tenantSchema, kpis.getNoSupplySchemeIds(), false);
            anomalyRows = buildAnomalyRows(tenantSchema, kpis);
            localPath = dailyReportPdfService.generate(
                    kpis, officerUserId, officerName, officerUserType, noSupplyRows, anomalyRows);
        } catch (Exception generateEx) {
            // Row lookup or PDF rendering failed: tag the outcome so it is counted like every other
            // terminal state, then rethrow so the event is still retried.
            log.error("[Router/DAILY_REPORT] corr={} result=FAILED_GENERATION role={} tenant={} officer={} — {}",
                    corr, role, tenantId, officerUserId, generateEx.getMessage(), generateEx);
            throw generateEx;
        }
        // The PDF now exists on disk. Logged before upload/delivery so a report that is built but never
        // delivered is still counted as generated — that gap is the signal worth spotting.
        log.info("[Router/DAILY_REPORT] corr={} result=GENERATED role={} tenant={} officer={}"
                        + " noSupplyRows={} anomalyRows={}",
                corr, role, tenantId, officerUserId, noSupplyRows.size(), anomalyRows.size());

        LocalDate reportDate = LocalDate.parse(kpis.getReportDate());
        // The path the PDF service actually wrote to, not one rebuilt from escalation.report.dir: the
        // daily report has its own DAILY_REPORT_DIR, and re-deriving the path sent the upload looking
        // in the wrong directory in any environment that set it.
        String filename = localPath.getFileName().toString();
        String minioUrl;
        try {
            minioUrl = minioStorageService.upload(localPath, ReportFileNaming.DAILY_BUCKET,
                    ReportFileNaming.dailyObjectKey(officerUserType, filename, reportDate));
        } catch (Exception uploadEx) {
            log.error("[Router/DAILY_REPORT] corr={} result=FAILED_UPLOAD role={} tenant={} officer={},"
                            + " retaining local PDF for recovery: {} — {}",
                    corr, role, tenantId, officerUserId, localPath, uploadEx.getMessage());
            throw uploadEx;
        }
        deleteLocalReport(localPath, corr, ReportKind.DAILY.tag());

        ReportLogCtx logCtx = new ReportLogCtx(ReportKind.DAILY, corr, role, tenantId, officerUserId);
        ReportSendOutcome outcome =
                whatsAppChannel.sendDailyReport(contactId, minioUrl, officerUserType, reportDate, officerName);
        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (!outcome.accepted()) {
            reportFailedDelivery(logCtx, outcome.failure(), reportDate, loggableUrl(minioUrl));
            return;
        }
        logSendResult(logCtx, outcome.result(), contactId, noSupplyRows.size(), tookMs, loggableUrl(minioUrl));
    }

    /**
     * Renders and delivers the Weekly Water Service Situation Report for one officer, in whichever
     * layout their role calls for.
     *
     * <p>Mirrors the daily handler's order deliberately: validate, resolve the officer, resolve the
     * Glific contact id <em>before</em> rendering, then build → upload → send. Resolving the contact
     * first means a dead end costs no PDF render and no MinIO upload.</p>
     */
    private void handleWeeklySituationReport(JsonNode root) throws Exception {
        int tenantId = root.path("tenantId").asInt(0);
        String tenantSchema = root.path("tenantSchema").asText("");
        long officerUserId = root.path("officerUserId").asLong(0);
        String officerUserType = root.path("officerUserType").asText("").trim();
        String corr = root.path("correlationId").asText("");
        String role = officerUserType.isEmpty() ? "UNKNOWN" : officerUserType;
        long startNanos = System.nanoTime();

        if (tenantSchema.isBlank() || !tenantSchema.matches(SCHEMA_PATTERN) || officerUserId <= 0) {
            log.warn("[Router/WEEKLY_REPORT] corr={} result=SKIPPED_INVALID_EVENT role={} tenant={}",
                    corr, role, tenantId);
            return;
        }
        if (!root.hasNonNull("kpis")) {
            log.warn("[Router/WEEKLY_REPORT] corr={} result=SKIPPED_NO_KPIS role={} tenant={} officer={}",
                    corr, role, tenantId, officerUserId);
            return;
        }

        log.info("[Router/WEEKLY_REPORT] corr={} received: tenant={} officer={} role={}",
                corr, tenantId, officerUserId, role);

        WeeklyReportKpis kpis = objectMapper.treeToValue(root.path("kpis"), WeeklyReportKpis.class);
        if (!isRenderableWeeklyKpis(kpis)) {
            log.warn("[Router/WEEKLY_REPORT] corr={} result=SKIPPED_MALFORMED_KPIS role={} tenant={} officer={}"
                    + " (non-retryable)", corr, role, tenantId, officerUserId);
            return;
        }

        OfficerContact officer = resolveOfficerContactById(tenantSchema, officerUserId);
        if (officer.contactId() == null && (officer.phone() == null || officer.phone().isBlank())) {
            log.warn("[Router/WEEKLY_REPORT] corr={} result=SKIPPED_NO_CONTACT role={} tenant={} officer={} schema={}",
                    corr, role, tenantId, officerUserId, tenantSchema);
            return;
        }

        String officerName = officer.name() != null ? officer.name() : "Officer";
        long contactId = resolveContactIdOrOptIn(officer, tenantSchema, officerUserId);
        if (contactId <= 0 && whatsAppSender.isWeeklyReportDeliveryEnabled()) {
            log.error("[Router/WEEKLY_REPORT] corr={} result=SKIPPED_NO_CONTACT_ID role={} tenant={} officer={}"
                            + " — Glific opt-in returned no contact id (non-retryable)",
                    corr, role, tenantId, officerUserId);
            return;
        }

        boolean sdo = "SUB_DIVISIONAL_OFFICER".equalsIgnoreCase(officerUserType);
        List<ReportSchemeRow> noSupplyRows;
        List<ReportSchemeRow> lowSupplyDaysRows;
        List<ReportSchemeRow> lowLpcdRows;
        List<WeeklyReportOfficerRow> officerRows;
        java.nio.file.Path localPath;
        try {
            noSupplyRows = buildSchemeRows(tenantSchema, kpis.getNoSupplySchemeIds(), sdo);
            // The 1-3 day band is a Section Officer section only; resolving it for an SDO would be
            // several queries for rows nobody draws.
            lowSupplyDaysRows = sdo ? List.of()
                    : buildSchemeRows(tenantSchema, kpis.getLowSupplyDaysSchemeIds(), false);
            lowLpcdRows = buildSchemeRows(tenantSchema, kpis.getLowLpcdSchemeIds(), sdo);
            officerRows = sdo ? buildWeeklyOfficerRows(tenantSchema, kpis) : List.of();
            localPath = weeklyReportPdfService.generate(kpis, officerUserId, officerName, officerUserType,
                    noSupplyRows, lowSupplyDaysRows, lowLpcdRows, officerRows);
        } catch (Exception generateEx) {
            log.error("[Router/WEEKLY_REPORT] corr={} result=FAILED_GENERATION role={} tenant={} officer={} — {}",
                    corr, role, tenantId, officerUserId, generateEx.getMessage(), generateEx);
            throw generateEx;
        }
        log.info("[Router/WEEKLY_REPORT] corr={} result=GENERATED role={} tenant={} officer={}"
                        + " noSupplyRows={} lowDaysRows={} lowLpcdRows={} officerRows={}",
                corr, role, tenantId, officerUserId,
                noSupplyRows.size(), lowSupplyDaysRows.size(), lowLpcdRows.size(), officerRows.size());

        LocalDate weekStart = LocalDate.parse(kpis.getWeekStart());
        LocalDate weekEnd = LocalDate.parse(kpis.getWeekEnd());
        // As for the daily report: the weekly PDF's directory resolves through
        // weekly-report.report.dir → daily-report.report.dir → escalation.report.dir, so only the
        // service that wrote the file knows where it landed.
        String filename = localPath.getFileName().toString();
        String minioUrl;
        try {
            minioUrl = minioStorageService.upload(localPath, ReportFileNaming.WEEKLY_BUCKET,
                    ReportFileNaming.weeklyObjectKey(officerUserType, filename, weekStart, weekEnd));
        } catch (Exception uploadEx) {
            log.error("[Router/WEEKLY_REPORT] corr={} result=FAILED_UPLOAD role={} tenant={} officer={},"
                            + " retaining local PDF for recovery: {} — {}",
                    corr, role, tenantId, officerUserId, localPath, uploadEx.getMessage());
            throw uploadEx;
        }
        deleteLocalReport(localPath, corr, ReportKind.WEEKLY.tag());

        // Tagged WEEKLY so the terminal SENT / SUPPRESSED / FAILED_DELIVERY / DELIVERY_UNCONFIRMED lines
        // land under [Router/WEEKLY_REPORT] alongside this officer's GENERATED line, rather than under
        // the daily prefix the shared helpers are also used by.
        ReportLogCtx logCtx = new ReportLogCtx(ReportKind.WEEKLY, corr, role, tenantId, officerUserId);
        ReportSendOutcome outcome =
                whatsAppChannel.sendWeeklyReport(contactId, minioUrl, officerUserType, weekStart, officerName);
        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (!outcome.accepted()) {
            reportFailedDelivery(logCtx, outcome.failure(), weekStart, loggableUrl(minioUrl));
            return;
        }
        logSendResult(logCtx, outcome.result(), contactId, noSupplyRows.size(), tookMs, loggableUrl(minioUrl));
    }

    /**
     * Which situation report a router line is about.
     *
     * <p>Selects the {@code [Router/…]} prefix and the name of the date field on the terminal lines, so
     * a weekly outcome is counted as a weekly one. The send-logging helpers below are shared by both
     * reports and used to hard-code the daily prefix, which folded every weekly {@code SENT} into the
     * daily total and left every weekly {@code GENERATED} with no {@code SENT} to reconcile against. A
     * Section Officer now receives both reports, so the prefix is the only thing that can tell their
     * lines apart.</p>
     */
    private enum ReportKind {
        DAILY("reportDate"),
        WEEKLY("weekStart");

        private final String periodField;

        ReportKind(String periodField) {
            this.periodField = periodField;
        }

        /** The {@code [Router/<tag>]} prefix, and the tag {@link #deleteLocalReport} logs under. */
        String tag() {
            return name() + "_REPORT";
        }

        /** "daily" / "weekly", for prose inside an exception message. */
        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Names the date the terminal lines carry: the day a daily report covers, the first day a weekly
         * one opens on. Two different facts, so they do not share a field name.
         */
        String periodField() {
            return periodField;
        }
    }

    /**
     * The fields every {@code [Router/…_REPORT]} line opens with, kept together so the
     * {@code result= role= tenant= officer=} adjacency the log-counting recipes grep for cannot drift
     * apart between the handler and the lines it delegates.
     */
    private record ReportLogCtx(ReportKind kind, String corr, String role, int tenantId, long officerUserId) {}

    /**
     * Logs a rejected send and decides whether the event may be retried. Shared by both reports; the
     * {@link ReportKind} on the context picks the {@code [Router/…]} prefix and the date field name, so
     * a weekly failure is never counted as a daily one.
     *
     * <p>Emits <strong>exactly one</strong> terminal {@code result=} line per failed send, because the
     * log-counting recipes add those tokens up: an ambiguous send that logged both
     * {@code FAILED_DELIVERY} and {@code DELIVERY_UNCONFIRMED} was counted twice, and inflated the
     * definite-failure total with sends that may well have arrived.</p>
     *
     * <p>Three outcomes, only one of which is retried:</p>
     * <ul>
     *   <li>{@link #isAmbiguousDelivery} ({@code TIMEOUT}, {@code SEND_NO_MESSAGE_ID}) — Glific may
     *       already hold the message, so re-driving the event would send the officer a second copy of
     *       the same report, which is worse than the missing confirmation it was trying to fix.
     *       Recorded as {@code DELIVERY_UNCONFIRMED} for reconciliation.</li>
     *   <li>{@link WhatsAppSendStage#CONFIG} — a definite rejection that never reached Glific, and one no
     *       retry can repair: the template id, contact id or MinIO URL prefix is wrong on our side.
     *       Retrying only stalls the partition until the configuration changes, so it is terminal.</li>
     *   <li>Everything else ({@code MEDIA_REGISTER}, {@code SEND}) — a definite rejection a retry can
     *       plausibly repair, so it rethrows for the Kafka container's retry policy.</li>
     * </ul>
     */
    private void reportFailedDelivery(ReportLogCtx ctx, ReportSendOutcome.Failure failure,
                                      LocalDate period, String loggableUrl) {
        String tag = ctx.kind().tag();
        // stage= and glificErrorKey= are appended *after* officer= on every branch below.
        if (isAmbiguousDelivery(failure.stage())) {
            log.warn("[Router/{}] corr={} result=DELIVERY_UNCONFIRMED role={} tenant={} officer={}"
                            + " stage={} glificErrorKey={} {}={} (non-retryable) — Glific may already"
                            + " have sent this report, so the event is not retried. Settle it against"
                            + " Glific's own delivery status for this officer; see"
                            + " WhatsAppDeliveryReconciliationService ({})",
                    tag, ctx.corr(), ctx.role(), ctx.tenantId(), ctx.officerUserId(),
                    failure.stage(), failure.errorKeyForLog(), ctx.kind().periodField(), period, loggableUrl);
            return;
        }
        log.error("[Router/{}] corr={} result=FAILED_DELIVERY role={} tenant={} officer={}"
                        + " stage={} glificErrorKey={}",
                tag, ctx.corr(), ctx.role(), ctx.tenantId(), ctx.officerUserId(),
                failure.stage(), failure.errorKeyForLog());
        if (failure.stage() == WhatsAppSendStage.CONFIG) {
            log.error("[Router/{}] corr={} stage=CONFIG {}={} (non-retryable) — the send"
                            + " never reached Glific because our own template id, contact id or MinIO URL"
                            + " prefix is wrong. A retry cannot repair that, so the event is not redriven:"
                            + " fix the configuration, then replay this officer's report ({})",
                    tag, ctx.corr(), ctx.kind().periodField(), period, loggableUrl);
            return;
        }
        throw new IllegalStateException("[Router/" + tag + "] corr=" + ctx.corr()
                + " WhatsApp " + ctx.kind().label() + " report delivery failed at stage=" + failure.stage());
    }

    /**
     * Logs an accepted send — or a suppressed one, which is not the same event and no longer shares a
     * line with it. A dry-run reached no Glific mutation at all: it has no {@code GLIFIC_ACCEPTED}
     * stage, no message id and nothing for reconciliation to match, so counting it as {@code SENT}
     * reported a muted deployment as a delivering one.
     *
     * <p>Shared by both reports, prefixed by the context's {@link ReportKind}. {@code noSupplyRows=}
     * carries the same count as the matching {@code result=GENERATED} line's field of that name, so the
     * two can be lined up per officer.</p>
     */
    private void logSendResult(ReportLogCtx ctx, WhatsAppSendResult sendResult, long contactId,
                               int noSupplyRows, long tookMs, String loggableUrl) {
        String tag = ctx.kind().tag();
        if (sendResult.isSuppressed()) {
            log.info("[Router/{}] corr={} result=SUPPRESSED role={} tenant={} officer={}"
                            + " mode={} noSupplyRows={} tookMs={} ({})",
                    tag, ctx.corr(), ctx.role(), ctx.tenantId(), ctx.officerUserId(),
                    sendResult.modeForLog(), noSupplyRows, tookMs, loggableUrl);
            return;
        }
        // result=SENT means Glific ACCEPTED the send — it is not a WhatsApp delivery confirmation.
        // glificMsgId is what lets the delivery status Gupshup and Meta later report to Glific be
        // matched back to this officer; see WhatsAppDeliveryReconciliationService. Every new field goes after officer= to preserve
        // the field adjacency the log-counting recipes rely on.
        log.info("[Router/{}] corr={} result=SENT role={} tenant={} officer={}"
                        + " stage=GLIFIC_ACCEPTED glificMsgId={} glificContactId={} mode={} templateId={}"
                        + " noSupplyRows={} tookMs={} ({})",
                tag, ctx.corr(), ctx.role(), ctx.tenantId(), ctx.officerUserId(),
                sendResult.messageIdForLog(), contactId, sendResult.modeForLog(), sendResult.templateIdForLog(),
                noSupplyRows, tookMs, loggableUrl);
    }

    /**
     * Stages after which Glific may already have created and sent the message, so the event must not be
     * retried: a {@code block()} timeout, and a mutation that returned no errors but no message id
     * either. Both leave delivery unconfirmed rather than failed, and only Glific can settle which.
     */
    private static boolean isAmbiguousDelivery(WhatsAppSendStage stage) {
        return stage == WhatsAppSendStage.TIMEOUT || stage == WhatsAppSendStage.SEND_NO_MESSAGE_ID;
    }

    /** A MinIO URL with any presigned query string stripped, so a signature never reaches a log line. */
    private static String loggableUrl(String url) {
        return url.replaceFirst(URL_QUERY_SUFFIX, "");
    }

    /**
     * Resolves each analytics Priority Action (schemeId + issue + daysNoSupply) into a printable row
     * by looking up the scheme's name + IMIS id and its pump operators (Jal Mitras) from the
     * operational schema. Schemes that can't be resolved are still shown with the ids we have.
     */
    /**
     * Resolves analytics scheme ids into printable rows: scheme name, IMIS id, Jal Mitra contacts,
     * and — for the SDO report — the owning Section Officer and the villages the scheme serves.
     *
     * <p>Every lookup is batched over the whole id set (three or five queries for the section, not
     * per row): a Section Officer with two hundred schemes with no supply by 16:00 is an ordinary
     * afternoon, and a per-row lookup would turn that into six hundred round trips.</p>
     *
     * <p>A scheme whose name cannot be resolved still produces a row, labelled with its id. Dropping
     * it would silently shorten a list whose whole purpose is to be acted on.</p>
     */
    private List<ReportSchemeRow> buildSchemeRows(String tenantSchema, List<Integer> schemeIds, boolean withSdoDetail) {
        if (schemeIds == null || schemeIds.isEmpty()) {
            return List.of();
        }
        Set<Integer> ids = new LinkedHashSet<>(schemeIds);
        Map<Integer, SchemeLabel> labels = resolveSchemeLabels(tenantSchema, ids);
        Map<Integer, List<OperatorContact>> operators = resolvePumpOperators(tenantSchema, ids);
        Map<Integer, List<OperatorContact>> sectionOfficers =
                withSdoDetail ? resolveSectionOfficers(tenantSchema, ids) : Map.of();
        Map<Integer, List<String>> villages =
                withSdoDetail ? resolveVillages(tenantSchema, ids) : Map.of();

        List<ReportSchemeRow> rows = new ArrayList<>();
        for (Integer schemeId : ids) {
            SchemeLabel label = labels.getOrDefault(schemeId, new SchemeLabel(null, null));
            rows.add(ReportSchemeRow.builder()
                    .schemeId(schemeId)
                    .schemeName(label.schemeName() != null ? label.schemeName() : ("#" + schemeId))
                    .imisId(label.centreSchemeId() != null ? label.centreSchemeId() : "")
                    .jalMitraNames(joinNames(operators.get(schemeId)))
                    .jalMitraMobiles(joinPhones(operators.get(schemeId)))
                    .sectionOfficerNames(joinNames(sectionOfficers.get(schemeId)))
                    .sectionOfficerMobiles(joinPhones(sectionOfficers.get(schemeId)))
                    .villageNames(villages.containsKey(schemeId) ? String.join(", ", villages.get(schemeId)) : "")
                    .build());
        }
        return rows;
    }

    /**
     * The daily report's anomalous-submissions rows: one per (scheme, anomaly type), so a scheme with
     * three different problems is three lines. The scheme lookups are batched once across the whole
     * section even though a scheme may appear on several rows.
     */
    private List<ReportSchemeRow> buildAnomalyRows(String tenantSchema, DailyReportKpis kpis) {
        List<DailyReportKpis.SchemeAnomaly> anomalies = kpis.getSchemeAnomalies();
        if (anomalies == null || anomalies.isEmpty()) {
            return List.of();
        }
        List<Integer> distinctIds = anomalies.stream().map(DailyReportKpis.SchemeAnomaly::getSchemeId)
                .distinct().toList();
        Map<Integer, ReportSchemeRow> byScheme = new LinkedHashMap<>();
        for (ReportSchemeRow row : buildSchemeRows(tenantSchema, distinctIds, false)) {
            byScheme.put(row.getSchemeId(), row);
        }

        List<ReportSchemeRow> rows = new ArrayList<>();
        for (DailyReportKpis.SchemeAnomaly anomaly : anomalies) {
            ReportSchemeRow base = byScheme.get(anomaly.getSchemeId());
            if (base == null) {
                continue;
            }
            rows.add(ReportSchemeRow.builder()
                    .schemeId(base.getSchemeId())
                    .schemeName(base.getSchemeName())
                    .imisId(base.getImisId())
                    .jalMitraNames(base.getJalMitraNames())
                    .jalMitraMobiles(base.getJalMitraMobiles())
                    .anomalyType(AnomalyLabels.label(anomaly.getType()))
                    .build());
        }
        return rows;
    }

    /**
     * Resolves each weekly {@code SectionOfficerWeekSummary} into a printable performance row by
     * looking up the officer's decrypted name and mobile. Officer order from analytics is preserved.
     */
    private List<WeeklyReportOfficerRow> buildWeeklyOfficerRows(String tenantSchema, WeeklyReportKpis kpis) {
        List<WeeklyReportKpis.SectionOfficerWeekSummary> summaries = kpis.getSectionOfficerSummaries();
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        Set<Long> officerIds = new LinkedHashSet<>();
        for (WeeklyReportKpis.SectionOfficerWeekSummary summary : summaries) {
            officerIds.add(summary.getOfficerUserId());
        }
        Map<Long, OfficerContact> contacts = resolveOfficerContactsByIds(tenantSchema, officerIds);

        List<WeeklyReportOfficerRow> rows = new ArrayList<>();
        for (WeeklyReportKpis.SectionOfficerWeekSummary summary : summaries) {
            OfficerContact contact = contacts.get(summary.getOfficerUserId());
            rows.add(WeeklyReportOfficerRow.builder()
                    .officerUserId(summary.getOfficerUserId())
                    .name(contact != null && contact.name() != null
                            ? contact.name() : ("#" + summary.getOfficerUserId()))
                    .mobile(contact != null && contact.phone() != null ? contact.phone() : "")
                    .totalSchemes(summary.getTotalSchemes())
                    .schemesSupplying(summary.getSchemesSupplying())
                    .schemesNotSupplying(summary.getSchemesNotSupplying())
                    .schemesLowLpcd(summary.getSchemesLowLpcd())
                    .avgLpcd(summary.getAvgLpcd())
                    .build());
        }
        return rows;
    }

    private static String joinNames(List<OperatorContact> contacts) {
        if (contacts == null) {
            return "";
        }
        return contacts.stream().map(OperatorContact::name)
                .filter(n -> n != null && !n.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String joinPhones(List<OperatorContact> contacts) {
        if (contacts == null) {
            return "";
        }
        return contacts.stream().map(OperatorContact::phone)
                .filter(phone -> phone != null && !phone.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Batch-resolves each officer's decrypted display name + phone from {@code <tenantSchema>.user_table}
     * by user id, keyed by user id. {@code tenantSchema} is validated against {@link #SCHEMA_PATTERN} by
     * the caller (schema names are SQL identifiers and cannot be bound as {@code ?}); the ids bind as
     * parameters. Mirrors {@link #resolveOfficerContactById} for many ids in one query.
     */
    @SuppressWarnings("java:S2077")
    private Map<Long, OfficerContact> resolveOfficerContactsByIds(String tenantSchema, Set<Long> officerIds) {
        if (officerIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT id, whatsapp_connection_id, title, phone_number FROM " + tenantSchema
                + ".user_table WHERE id IN (" + placeholders(officerIds.size()) + ")";
        List<OfficerContactRow> rows = jdbcTemplate.query(sql,
                (rs, n) -> new OfficerContactRow(rs.getLong("id"), new OfficerContact(
                        rs.getObject("whatsapp_connection_id", Long.class),
                        piiEncryptionService.safeDecrypt(rs.getString("title")),
                        piiEncryptionService.safeDecrypt(rs.getString("phone_number")))),
                officerIds.toArray());
        Map<Long, OfficerContact> byId = new LinkedHashMap<>();
        for (OfficerContactRow row : rows) {
            byId.put(row.userId(), row.contact());
        }
        return byId;
    }

    /** Intermediate row carrying the officer user id alongside its resolved contact, for batch grouping. */
    private record OfficerContactRow(long userId, OfficerContact contact) {}

    /**
     * A KPI payload is renderable only when both dates are present and ISO-parseable and both
     * day-KPI blocks exist. Guarding here keeps a malformed/incomplete payload from surfacing as a
     * {@link DateTimeParseException} or NPE inside PDF rendering, which the container would retry;
     * instead it is treated as a permanent, non-retryable skip like the other checks above.
     */
    private boolean isRenderableKpis(DailyReportKpis kpis) {
        // The report date is the one field with no safe default: it names the filename, the object key
        // and the WhatsApp message. Everything else can legitimately be zero.
        return kpis != null && isIsoDate(kpis.getReportDate());
    }

    /**
     * A weekly payload is renderable only with all four week bounds present and ISO-parseable. The
     * comparison week's bounds count too: the PDF's summary table parses them for its column header, so
     * a malformed one throws {@link DateTimeParseException} mid-render just as the current week's would.
     */
    private boolean isRenderableWeeklyKpis(WeeklyReportKpis kpis) {
        return kpis != null
                && isIsoDate(kpis.getWeekStart()) && isIsoDate(kpis.getWeekEnd())
                && isIsoDate(kpis.getPreviousWeekStart()) && isIsoDate(kpis.getPreviousWeekEnd());
    }

    /**
     * Deletes the rendered PDF once it is safely in MinIO. A failure here is logged and swallowed: the
     * upload already succeeded, so the officer's report is on its way, and the reaper sweeps whatever
     * is left behind.
     */
    private void deleteLocalReport(java.nio.file.Path localPath, String corr, String tag) {
        try {
            Files.deleteIfExists(localPath);
        } catch (Exception cleanupEx) {
            log.warn("[Router/{}] corr={} could not delete local PDF {}: {}",
                    tag, corr, localPath, cleanupEx.getMessage());
        }
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
        long contactId = whatsAppSender.optIn(officer.phone());
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
     * Batch-resolves scheme display name + IMIS id (centre_scheme_id) for the given scheme ids from the
     * operational schema, keyed by scheme id. {@code tenantSchema} is validated against
     * {@link #SCHEMA_PATTERN} by the caller (schema names are SQL identifiers and cannot be bound as
     * {@code ?}); the ids bind as parameters.
     */
    @SuppressWarnings("java:S2077")
    private Map<Integer, SchemeLabel> resolveSchemeLabels(String tenantSchema, Set<Integer> schemeIds) {
        if (schemeIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT id, scheme_name, centre_scheme_id FROM " + tenantSchema
                + ".scheme_master_table WHERE id IN (" + placeholders(schemeIds.size()) + ") AND deleted_at IS NULL";
        List<SchemeRow> rows = jdbcTemplate.query(sql,
                (rs, n) -> new SchemeRow(rs.getInt("id"),
                        new SchemeLabel(rs.getString("scheme_name"), rs.getString("centre_scheme_id"))),
                schemeIds.toArray());
        Map<Integer, SchemeLabel> byId = new LinkedHashMap<>();
        for (SchemeRow row : rows) {
            byId.put(row.schemeId(), row.label());
        }
        return byId;
    }

    /**
     * Batch-resolves all active PUMP_OPERATOR (Jal Mitra) users mapped to the given schemes, with
     * decrypted name + phone, grouped by scheme id. {@code tenantSchema} is validated by the caller;
     * the ids bind as parameters.
     */
    @SuppressWarnings("java:S2077")
    private Map<Integer, List<OperatorContact>> resolvePumpOperators(String tenantSchema, Set<Integer> schemeIds) {
        if (schemeIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT usm.scheme_id, u.title, u.phone_number FROM " + tenantSchema + ".user_scheme_mapping_table usm "
                + "JOIN " + tenantSchema + ".user_table u ON u.id = usm.user_id "
                + "JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type "
                + "WHERE usm.scheme_id IN (" + placeholders(schemeIds.size()) + ") AND UPPER(ut.c_name) = 'PUMP_OPERATOR' "
                + "AND usm.status = 1 AND u.status = 1 AND usm.deleted_at IS NULL AND u.deleted_at IS NULL "
                + "ORDER BY usm.scheme_id, u.id";
        List<OperatorRow> rows = jdbcTemplate.query(sql,
                (rs, n) -> new OperatorRow(rs.getInt("scheme_id"),
                        new OperatorContact(
                                piiEncryptionService.safeDecrypt(rs.getString("title")),
                                piiEncryptionService.safeDecrypt(rs.getString("phone_number")))),
                schemeIds.toArray());
        Map<Integer, List<OperatorContact>> byScheme = new LinkedHashMap<>();
        for (OperatorRow row : rows) {
            byScheme.computeIfAbsent(row.schemeId(), k -> new ArrayList<>()).add(row.contact());
        }
        return byScheme;
    }

    /** Builds a {@code ?, ?, ...} placeholder list of the given length for an {@code IN (...)} clause. */
    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /** Intermediate row carrying the scheme id alongside its resolved label, for batch grouping. */
    private record SchemeRow(int schemeId, SchemeLabel label) {}

    /**
     * Batch-resolves the active Section Officers mapped to each scheme, with decrypted name + phone.
     *
     * <p>Used only by the SDO weekly report, where a scheme row has to say <em>whose</em> scheme it is
     * — an SDO acts through their officers rather than directly on a scheme. A scheme mapped to two
     * Section Officers lists both; that is a data-quality condition in the mapping table, and hiding
     * one of them would misattribute the scheme.</p>
     */
    @SuppressWarnings("java:S2077")
    private Map<Integer, List<OperatorContact>> resolveSectionOfficers(String tenantSchema, Set<Integer> schemeIds) {
        if (schemeIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT usm.scheme_id, u.title, u.phone_number FROM " + tenantSchema + ".user_scheme_mapping_table usm "
                + "JOIN " + tenantSchema + ".user_table u ON u.id = usm.user_id "
                + "JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type "
                + "WHERE usm.scheme_id IN (" + placeholders(schemeIds.size()) + ") AND UPPER(ut.c_name) = 'SECTION_OFFICER' "
                + "AND usm.status = 1 AND u.status = 1 AND usm.deleted_at IS NULL AND u.deleted_at IS NULL "
                + "ORDER BY usm.scheme_id, u.id";
        List<OperatorRow> rows = jdbcTemplate.query(sql,
                (rs, n) -> new OperatorRow(rs.getInt("scheme_id"),
                        new OperatorContact(
                                piiEncryptionService.safeDecrypt(rs.getString("title")),
                                piiEncryptionService.safeDecrypt(rs.getString("phone_number")))),
                schemeIds.toArray());
        Map<Integer, List<OperatorContact>> byScheme = new LinkedHashMap<>();
        for (OperatorRow row : rows) {
            byScheme.computeIfAbsent(row.schemeId(), k -> new ArrayList<>()).add(row.contact());
        }
        return byScheme;
    }

    /**
     * Batch-resolves the village names each scheme serves.
     *
     * <p>There is no village table: villages are rows of {@code lgd_location_master_table} at the
     * deepest level of the per-tenant LGD tree, reached through {@code scheme_lgd_mapping_table}. A
     * scheme can serve several — which is exactly why the analytics scheme dimension fans out — and
     * all of them are returned, because an officer sent to a scheme needs to know every village it
     * covers.</p>
     */
    @SuppressWarnings("java:S2077")
    private Map<Integer, List<String>> resolveVillages(String tenantSchema, Set<Integer> schemeIds) {
        if (schemeIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT slm.scheme_id, lgd.title FROM " + tenantSchema + ".scheme_lgd_mapping_table slm "
                + "JOIN " + tenantSchema + ".lgd_location_master_table lgd ON lgd.id = slm.parent_lgd_id "
                + "WHERE slm.scheme_id IN (" + placeholders(schemeIds.size()) + ") "
                + "AND slm.deleted_at IS NULL AND lgd.deleted_at IS NULL AND lgd.status = 1 "
                + "ORDER BY slm.scheme_id, lgd.title";
        List<VillageRow> rows = jdbcTemplate.query(sql,
                (rs, n) -> new VillageRow(rs.getInt("scheme_id"), rs.getString("title")),
                schemeIds.toArray());
        Map<Integer, List<String>> byScheme = new LinkedHashMap<>();
        for (VillageRow row : rows) {
            if (row.title() == null || row.title().isBlank()) {
                continue;
            }
            byScheme.computeIfAbsent(row.schemeId(), k -> new ArrayList<>()).add(row.title());
        }
        return byScheme;
    }

    /** Intermediate row carrying the scheme id alongside one village name, for batch grouping. */
    private record VillageRow(int schemeId, String title) {}

    /** Intermediate row carrying the scheme id alongside one resolved operator, for batch grouping. */
    private record OperatorRow(int schemeId, OperatorContact contact) {}
}
