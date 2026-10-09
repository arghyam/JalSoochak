package org.arghyam.jalsoochak.message.channel.provider.glific;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppDeliveryStatusReader;
import org.arghyam.jalsoochak.message.dto.WhatsAppDeliveryOutcome;
import org.arghyam.jalsoochak.message.dto.WhatsAppMessageStatus;
import org.arghyam.jalsoochak.message.util.PhoneRedactor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads back what Gupshup and Meta told Glific about the messages we sent.
 *
 * <p>This is the half of the pipeline that never existed. {@code result=SENT} only ever meant "the
 * Glific GraphQL mutation returned no errors" — Gupshup and Meta act after that call returns and
 * report delivery status back to <em>Glific</em>, never to us. This service asks.</p>
 *
 * <h2>Why it is stateless</h2>
 * <p>{@code MessageFilter} supports {@code dateRange} and {@code bspStatus}, so a window of messages
 * can be pulled straight from Glific with no local work-list. Nothing has to be remembered between
 * runs, and a restart loses nothing.</p>
 *
 * <h2>Why filtering happens client-side</h2>
 * <p>{@code MessageFilter} has <strong>no {@code templateId}</strong> and no filter-by-id-list, so a
 * window also contains nudges, login OTPs, flow traffic and inbound messages. Those are discarded here
 * against the configured daily-report template ids. Two guards matter:</p>
 * <ul>
 *   <li>{@code flow == OUTBOUND} — on an <em>inbound</em> message {@code receiver} is our own org
 *       contact, not an officer, so counting one would map a status to the wrong person entirely.</li>
 *   <li>{@code isHsm == true} — a session message is not a template send.</li>
 * </ul>
 *
 * <h2>Privacy</h2>
 * <p>Glific's {@code errors} payload carries the recipient's raw phone number in its
 * {@code destination} field. Only {@code code} and {@code reason} are lifted out, and the reason is
 * run through {@link PhoneRedactor} before it can reach a log line. The raw blob is never logged above
 * {@code DEBUG} and never leaves this class.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GlificDeliveryStatusReader implements WhatsAppDeliveryStatusReader {

    private static final String MESSAGES_QUERY = """
            query messages($filter: MessageFilter, $opts: Opts) {
              messages(filter: $filter, opts: $opts) {
                id
                bspMessageId
                bspStatus
                errors
                templateId
                isHsm
                flow
                insertedAt
                updatedAt
                receiver { id }
              }
            }""";

    private static final String MESSAGE_QUERY = """
            query message($id: ID!) {
              message(id: $id) {
                message {
                  id
                  bspMessageId
                  bspStatus
                  errors
                  templateId
                  isHsm
                  flow
                  insertedAt
                  updatedAt
                  receiver { id }
                }
                errors { key message }
              }
            }""";

    /**
     * Glific's hard server-side cap on {@code opts.limit}. Asking for more returns 50 rows, not an
     * error — so a page of 50 against a requested 250 looked like the last page, and every pass read
     * only the newest 50 messages of each status. Pages are sized and judged against this instead.
     */
    static final int MAX_PAGE_SIZE = 50;

    private static final String COUNT_QUERY = """
            query countMessages($filter: MessageFilter) {
              countMessages(filter: $filter)
            }""";

    /**
     * The timestamp column {@code dateRange} filters on. {@code inserted_at} is when Glific created the
     * message row — stable for windowing a send run, unlike {@code updated_at} which moves every time a
     * status arrives and would let a message drift out of the window it was sent in.
     */
    static final String DEFAULT_DATE_COLUMN = "inserted_at";

    /** The column that moves every time a status arrives — what a "changed since" pass windows on. */
    static final String CHANGED_SINCE_COLUMN = "updated_at";

    /**
     * Every outbound {@code bspStatus}, in the order a message moves through them. Glific's
     * {@code MessageStatusEnum} is {@code CONTACT_OPT_OUT, DELETED, DELIVERED, ENQUEUED, ERROR, PLAYED,
     * REACHED, READ, RECEIVED, SEEN, SENT} (confirmed by introspection); {@code RECEIVED} and
     * {@code DELETED} are not deliveries, and {@code REACHED} is broadcast-level, not seen on a direct
     * template message.
     */
    static final List<String> STATUSES_IN_PROGRESSION = List.of(
            "ENQUEUED", "SENT", "DELIVERED", "READ", "SEEN", "PLAYED", "ERROR", "CONTACT_OPT_OUT");

    private final GlificGraphQLClient client;
    private final ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     *
     * <p>Glific's {@code countMessages} takes only a filter and cannot filter by template, so this
     * counts nudges, OTPs and inbound traffic too.</p>
     */
    @Override
    public int countMessages(Instant from, Instant to, String bspStatus, String dateColumn) {
        JsonNode response = client.execute(COUNT_QUERY,
                Map.of("filter", buildFilter(from, to, bspStatus, dateColumn)));
        JsonNode count = response.path("countMessages");
        return count.isNumber() ? count.asInt() : -1;
    }

    @Override
    public int maxPageSize() {
        return MAX_PAGE_SIZE;
    }

    @Override
    public String providerId() {
        return GlificWhatsAppSender.PROVIDER_ID;
    }

    @Override
    public List<String> statusesInProgression() {
        return STATUSES_IN_PROGRESSION;
    }

    @Override
    public Optional<String> changedSinceColumn() {
        return Optional.of(CHANGED_SINCE_COLUMN);
    }

    /**
     * Maps one Glific {@code bspStatus} onto our vocabulary. An unrecognised value yields
     * {@link WhatsAppDeliveryOutcome#UNKNOWN_STATUS} rather than an exception.
     */
    static WhatsAppDeliveryOutcome outcomeOf(String bspStatus) {
        if (bspStatus == null || bspStatus.isBlank()) {
            return WhatsAppDeliveryOutcome.UNKNOWN_STATUS;
        }
        return switch (bspStatus.trim().toUpperCase(Locale.ROOT)) {
            case "DELIVERED" -> WhatsAppDeliveryOutcome.DELIVERED;
            case "READ", "SEEN", "PLAYED" -> WhatsAppDeliveryOutcome.READ;
            case "ERROR", "CONTACT_OPT_OUT" -> WhatsAppDeliveryOutcome.DELIVERY_FAILED;
            // ENQUEUED = still at Glific; SENT = Meta has it but has not delivered it;
            // REACHED = broadcast-level, not expected on a direct HSM.
            case "ENQUEUED", "SENT", "REACHED" -> WhatsAppDeliveryOutcome.PENDING;
            // RECEIVED is inbound (contact → us); DELETED was removed at Glific.
            case "RECEIVED", "DELETED" -> WhatsAppDeliveryOutcome.IGNORED;
            default -> WhatsAppDeliveryOutcome.UNKNOWN_STATUS;
        };
    }

    @Override
    public Optional<WhatsAppMessageStatus> fetchMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        JsonNode response = client.execute(MESSAGE_QUERY, Map.of("id", messageId));
        JsonNode node = response.path("message").path("message");
        if (node.isMissingNode() || node.isNull() || node.path("id").isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(toStatus(node));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages hold at most {@link #MAX_PAGE_SIZE}, whatever {@code pageSize} asks for, and a page is the
     * last only when it is shorter than that.</p>
     */
    @Override
    public List<WhatsAppMessageStatus> fetchMessages(Instant from, Instant to, String bspStatus,
                                                     String dateColumn, int pageSize, int maxPages) {
        List<WhatsAppMessageStatus> all = new ArrayList<>();
        String column = columnOrDefault(dateColumn);
        Map<String, Object> filter = buildFilter(from, to, bspStatus, column);
        int effectivePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        for (int page = 0; page < maxPages; page++) {
            int offset = page * effectivePageSize;
            JsonNode response = client.execute(MESSAGES_QUERY, Map.of(
                    "filter", filter,
                    "opts", Map.of(
                            "limit", effectivePageSize,
                            "offset", offset,
                            "order", "DESC",
                            "orderWith", column)));
            JsonNode messages = response.path("messages");
            if (!messages.isArray() || messages.isEmpty()) {
                return all;
            }
            for (JsonNode node : messages) {
                all.add(toStatus(node));
            }
            if (messages.size() < effectivePageSize) {
                return all;
            }
        }
        log.warn("[WhatsAppStatus] Hit the {}-page cap for bspStatus={} in window {}→{}; results are"
                        + " TRUNCATED and the counts below understate reality. Raise"
                        + " whatsapp.status.reconcile.max-pages or narrow window-hours.",
                maxPages, bspStatus, from, to);
        return all;
    }

    private Map<String, Object> buildFilter(Instant from, Instant to, String bspStatus, String dateColumn) {
        Map<String, Object> dateRange = new LinkedHashMap<>();
        dateRange.put("column", columnOrDefault(dateColumn));
        dateRange.put("from", from.toString());
        dateRange.put("to", to.toString());
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("dateRange", dateRange);
        if (bspStatus != null && !bspStatus.isBlank()) {
            filter.put("bspStatus", bspStatus);
        }
        return filter;
    }

    private static String columnOrDefault(String dateColumn) {
        return dateColumn == null || dateColumn.isBlank() ? DEFAULT_DATE_COLUMN : dateColumn;
    }

    /** Maps one raw Glific message node onto our record, extracting the failure code and reason. */
    private WhatsAppMessageStatus toStatus(JsonNode node) {
        String bspStatus = node.path("bspStatus").asText(null);
        GlificFailure failure = parseFailure(node.path("errors"), bspStatus);
        return new WhatsAppMessageStatus(
                node.path("id").asText(null),
                node.path("bspMessageId").asText(null),
                bspStatus,
                node.hasNonNull("templateId") ? node.get("templateId").asText() : null,
                node.path("isHsm").asBoolean(false),
                node.path("flow").asText(null),
                node.path("receiver").path("id").isMissingNode() ? null
                        : parseContactId(node.path("receiver").path("id").asText(null)),
                outcomeOf(bspStatus),
                failure.code(),
                failure.reason());
    }

    /** Glific returns ids as GraphQL {@code ID} (a string); a non-numeric one is not a contact we know. */
    private static Long parseContactId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The two fields worth keeping out of a BSP failure payload. */
    private record GlificFailure(String code, String reason) {
        static final GlificFailure NONE = new GlificFailure(null, null);
    }

    /**
     * Pulls the failure code and reason out of Glific's {@code errors} payload.
     *
     * <p>{@code errors} is a {@code Json} scalar returned as an <em>escaped JSON string</em>, so it
     * needs a second parse. The nesting is real and doubled:</p>
     * <pre>
     * {"payload": {"payload": {"reason": "...", "code": 131026},
     *              "destination": "91XXXXXXXXXX"}}
     * </pre>
     *
     * <p>Only {@code code} and {@code reason} are taken. {@code destination} is the recipient's phone
     * number and is deliberately left behind; the reason is redacted anyway, in case a future BSP
     * message embeds a number in its text.</p>
     */
    private GlificFailure parseFailure(JsonNode errorsNode, String bspStatus) {
        if (errorsNode == null || errorsNode.isMissingNode() || errorsNode.isNull()) {
            return defaultFailureFor(bspStatus);
        }
        JsonNode parsed;
        try {
            // Normally a TextNode holding JSON; tolerate an already-parsed object in case the scalar's
            // serialisation differs across Glific versions.
            parsed = errorsNode.isTextual() ? objectMapper.readTree(errorsNode.asText()) : errorsNode;
        } catch (Exception e) {
            // Never log the blob itself — it carries the recipient's number.
            log.debug("[WhatsAppStatus] Could not parse the errors payload ({}); falling back to bspStatus",
                    e.getMessage());
            return defaultFailureFor(bspStatus);
        }
        if (parsed == null || parsed.isMissingNode() || parsed.isNull()) {
            return defaultFailureFor(bspStatus);
        }
        JsonNode inner = parsed.path("payload").path("payload");
        String code = inner.path("code").isMissingNode() ? null : inner.path("code").asText(null);
        String reason = inner.path("reason").asText(null);
        if (code == null && reason == null) {
            return defaultFailureFor(bspStatus);
        }
        return new GlificFailure(code, PhoneRedactor.redact(reason));
    }

    /**
     * What to report when there is no usable {@code errors} payload. {@code CONTACT_OPT_OUT} carries
     * its meaning entirely in the status, so it gets a reason of its own rather than an empty one.
     */
    private static GlificFailure defaultFailureFor(String bspStatus) {
        if ("CONTACT_OPT_OUT".equalsIgnoreCase(bspStatus)) {
            return new GlificFailure("CONTACT_OPT_OUT", "contact opted out");
        }
        return GlificFailure.NONE;
    }
}
