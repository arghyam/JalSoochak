package org.arghyam.jalsoochak.message.channel.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SMSCountry's delivery-report callback as delivery reports.
 *
 * <p>Asked for on each send when {@code notifications.sms.delivery-report.url} is set (see
 * {@link SmsCountrySender}); that URL carries {@code token=<secret>} and the sender appends our
 * tracking reference as {@code ref}. SMSCountry signs nothing, so the token is the authentication: it is
 * compared, in constant time, by SHA-256 against {@code notifications.sms.webhook.token-hashes}
 * (comma-separated lowercase hex — hashes, never the token, as for the chatbot webhooks in
 * telemetry-service). {@code notifications.sms.webhook.mode}: {@code ENFORCE} (default), {@code AUDIT},
 * {@code OFF}; with no hash configured {@code ENFORCE} refuses everything.</p>
 *
 * <p>The report's exact shape is not documented to us, so it is read from wherever it arrives — a JSON
 * object or array, a form body, or query parameters — taking {@code MessageUUID}, {@code Status},
 * {@code StatusTime} and {@code Cost} only. The number and message text it may also carry are never
 * read. Pull ({@link SmsCountrySender#lookupStatuses}) covers SMS whether or not this is configured.</p>
 *
 * <p>The token is the platform's alone, so its reports may reach any schema ({@link ReceiptScope#ANY});
 * they still change only SMSCountry rows.</p>
 */
@Component
@Slf4j
public class SmsCountryDeliveryReceiptAdapter implements DeliveryReceiptAdapter {

    static final String TOKEN_PARAM = "token";
    static final String REF_PARAM = "ref";
    private static final Set<String> FIELDS = Set.of("MessageUUID", "Status", "StatusTime", "Cost");

    enum Mode { ENFORCE, AUDIT, OFF }

    private final ObjectMapper objectMapper;
    private final Set<String> tokenHashes;
    private final Mode mode;

    public SmsCountryDeliveryReceiptAdapter(ObjectMapper objectMapper,
            @Value("${notifications.sms.webhook.token-hashes:}") String tokenHashes,
            @Value("${notifications.sms.webhook.mode:ENFORCE}") String mode) {
        this.objectMapper = objectMapper;
        this.tokenHashes = tokenHashes == null || tokenHashes.isBlank() ? Set.of()
                : Arrays.stream(tokenHashes.split(",")).map(String::trim).map(h -> h.toLowerCase(Locale.ROOT))
                        .filter(h -> !h.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.mode = Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public String providerId() {
        return SmsCountrySender.PROVIDER_ID;
    }

    @Override
    public VerifiedReceipts parseAndVerify(DeliveryReceiptRequest request) {
        if (mode != Mode.OFF && !tokenMatches(request.query().get(TOKEN_PARAM))) {
            if (mode == Mode.ENFORCE) {
                throw new ReceiptRejectedException("SMS delivery report token missing or invalid");
            }
            log.warn("[Receipts] SMS delivery report token missing or invalid — accepted (mode=AUDIT)");
        }
        String trackingRef = request.query().get(REF_PARAM);
        List<DeliveryReceipt> receipts = new ArrayList<>();
        for (ObjectNode report : reportsIn(request)) {
            String uuid = report.path("MessageUUID").asText("");
            if (uuid.isBlank()) {
                continue;
            }
            DeliveryReceipt r = SmsCountrySender.toReceipt(uuid, report);
            receipts.add(new DeliveryReceipt(r.providerId(), r.providerMessageId(), trackingRef, r.state(),
                    r.providerStatus(), r.errorCode(), r.errorReason(), r.occurredAt(), r.cost(), r.costCurrency()));
        }
        // The token is the platform's: every account built by SmsCountrySenderFactory asks for its reports
        // at the one configured URL, so a tenant's own account holds no token of its own to scope by.
        return VerifiedReceipts.unscoped(receipts);
    }

    private boolean tokenMatches(String token) {
        if (token == null || token.isBlank() || tokenHashes.isEmpty()) {
            return false;
        }
        try {
            byte[] presented = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
            boolean match = false;
            for (String hash : tokenHashes) {
                match |= MessageDigest.isEqual(presented, hash.getBytes(StandardCharsets.UTF_8));
            }
            return match;
        } catch (Exception e) {
            return false;
        }
    }

    /** Every report in the request, reduced to the four fields read. */
    private List<ObjectNode> reportsIn(DeliveryReceiptRequest request) {
        List<ObjectNode> reports = new ArrayList<>();
        String body = new String(request.body(), StandardCharsets.UTF_8).trim();
        if (body.startsWith("{") || body.startsWith("[")) {
            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalArgumentException("SMS delivery report body is not JSON", e);
            }
            if (parsed.isArray()) {
                parsed.forEach(node -> reports.add(pick(node)));
            } else if (parsed.isObject()) {
                reports.add(pick(parsed));
            }
            return reports;
        }
        Map<String, String> fields = new LinkedHashMap<>(request.query());
        if (!body.isEmpty()) {
            fields.putAll(parseForm(body));
        }
        ObjectNode node = objectMapper.createObjectNode();
        FIELDS.forEach(f -> {
            String value = fields.get(f);
            if (value != null) {
                node.put(f, value);
            }
        });
        reports.add(node);
        return reports;
    }

    private ObjectNode pick(JsonNode source) {
        ObjectNode node = objectMapper.createObjectNode();
        FIELDS.forEach(f -> {
            if (source.hasNonNull(f)) {
                node.put(f, source.get(f).asText());
            }
        });
        return node;
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }
}
