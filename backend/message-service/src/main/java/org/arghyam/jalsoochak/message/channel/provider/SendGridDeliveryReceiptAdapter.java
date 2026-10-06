package org.arghyam.jalsoochak.message.channel.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.util.PhoneRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * SendGrid's Event Webhook as delivery reports.
 *
 * <p><b>Authentication.</b> SendGrid's Signed Event Webhook signs {@code timestamp + body} with ECDSA
 * (P-256, SHA-256) and sends the signature and timestamp as headers. The request is verified against
 * every key in {@code notifications.email.webhook.verification-keys} — base64 public keys as SendGrid's
 * console shows them, comma-separated, so a tenant's own SendGrid account can add its key beside the
 * platform's. {@code notifications.email.webhook.mode}: {@code ENFORCE} (default) refuses an unsigned or
 * mis-signed request, {@code AUDIT} logs and accepts it, {@code OFF} skips the check. With no key
 * configured, {@code ENFORCE} refuses everything — the webhook stays shut until it is set up.</p>
 *
 * <p>No freshness check on the timestamp: a replayed event can only re-apply a status the ledger already
 * holds, which changes nothing.</p>
 *
 * <p><b>Mapping.</b> {@code delivered} → DELIVERED; {@code open}, {@code click} → READ (an open needs
 * open tracking and is best effort); {@code bounce}, {@code dropped}, {@code blocked} → FAILED;
 * {@code processed}, {@code deferred} → PENDING. Spam reports and unsubscribes say nothing about
 * delivery and are ignored.</p>
 *
 * <p>Events carry the recipient's address; it is never read. A bounce reason often quotes it, so
 * addresses and phone numbers are scrubbed from the reason before it leaves here.</p>
 */
@Component
@Slf4j
public class SendGridDeliveryReceiptAdapter implements DeliveryReceiptAdapter {

    static final String SIGNATURE_HEADER = "X-Twilio-Email-Event-Webhook-Signature";
    static final String TIMESTAMP_HEADER = "X-Twilio-Email-Event-Webhook-Timestamp";
    private static final String EMAIL_PATTERN = "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+";

    enum Mode { ENFORCE, AUDIT, OFF }

    private final ObjectMapper objectMapper;
    private final List<PublicKey> keys;
    private final Mode mode;

    public SendGridDeliveryReceiptAdapter(ObjectMapper objectMapper,
            @Value("${notifications.email.webhook.verification-keys:}") String verificationKeys,
            @Value("${notifications.email.webhook.mode:ENFORCE}") String mode) {
        this.objectMapper = objectMapper;
        this.keys = parseKeys(verificationKeys);
        this.mode = Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public String providerId() {
        return SendGridMailSender.PROVIDER_ID;
    }

    @Override
    public List<DeliveryReceipt> parseAndVerify(DeliveryReceiptRequest request) {
        if (mode != Mode.OFF && !verified(request)) {
            if (mode == Mode.ENFORCE) {
                throw new ReceiptRejectedException("SendGrid event webhook signature missing or invalid");
            }
            log.warn("[Receipts] SendGrid event webhook signature missing or invalid — accepted (mode=AUDIT)");
        }
        JsonNode events;
        try {
            events = objectMapper.readTree(request.body());
        } catch (Exception e) {
            throw new IllegalArgumentException("SendGrid event webhook body is not JSON", e);
        }
        if (events == null || !events.isArray()) {
            throw new IllegalArgumentException("SendGrid event webhook body is not a JSON array");
        }
        List<DeliveryReceipt> receipts = new ArrayList<>();
        for (JsonNode event : events) {
            DeliveryReceipt receipt = toReceipt(event);
            if (receipt != null) {
                receipts.add(receipt);
            }
        }
        return receipts;
    }

    private boolean verified(DeliveryReceiptRequest request) {
        String signature = request.header(SIGNATURE_HEADER);
        String timestamp = request.header(TIMESTAMP_HEADER);
        if (signature == null || timestamp == null || keys.isEmpty()) {
            return false;
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(signature.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] timestampBytes = timestamp.getBytes(StandardCharsets.UTF_8);
        for (PublicKey key : keys) {
            try {
                Signature verifier = Signature.getInstance("SHA256withECDSA");
                verifier.initVerify(key);
                verifier.update(timestampBytes);
                verifier.update(request.body());
                if (verifier.verify(signatureBytes)) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("[Receipts] SendGrid signature check failed against one key: {}", e.getMessage());
            }
        }
        return false;
    }

    private static DeliveryReceipt toReceipt(JsonNode event) {
        String type = event.path("event").asText("").toLowerCase(Locale.ROOT);
        DeliveryState state = switch (type) {
            case "delivered" -> DeliveryState.DELIVERED;
            case "open", "click" -> DeliveryState.READ;
            case "bounce", "dropped", "blocked" -> DeliveryState.FAILED;
            case "processed", "deferred" -> DeliveryState.PENDING;
            default -> null;
        };
        String sgMessageId = event.path("sg_message_id").asText("");
        if (state == null || sgMessageId.isBlank()) {
            return null;
        }
        // sg_message_id is the X-Message-Id returned at send time plus a ".filter…" suffix.
        int dot = sgMessageId.indexOf('.');
        String messageId = dot > 0 ? sgMessageId.substring(0, dot) : sgMessageId;
        String trackingRef = event.path(SendGridMailSender.TRACKING_REF_ARG).asText(null);
        Instant at = event.path("timestamp").canConvertToLong()
                ? Instant.ofEpochSecond(event.path("timestamp").asLong())
                : null;
        String errorCode = null;
        String errorReason = null;
        if (state == DeliveryState.FAILED) {
            String classification = event.path("bounce_classification").asText(null);
            String status = event.path("status").asText(null);
            errorCode = classification != null ? classification : (status != null ? status : type);
            String reason = event.path("reason").asText(null);
            errorReason = reason == null ? null : PhoneRedactor.redact(reason.replaceAll(EMAIL_PATTERN, "<email>"));
        }
        return new DeliveryReceipt(SendGridMailSender.PROVIDER_ID, messageId, trackingRef, state, type,
                errorCode, errorReason, at, null, null);
    }

    private static List<PublicKey> parseKeys(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<PublicKey> parsed = new ArrayList<>();
        for (String raw : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            try {
                parsed.add(KeyFactory.getInstance("EC")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(raw))));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "notifications.email.webhook.verification-keys holds a value that is not a base64 EC public key", e);
            }
        }
        return List.copyOf(parsed);
    }
}
