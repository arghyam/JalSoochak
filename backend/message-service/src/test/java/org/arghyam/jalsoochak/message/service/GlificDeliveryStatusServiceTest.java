package org.arghyam.jalsoochak.message.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.arghyam.jalsoochak.message.channel.GlificGraphQLClient;
import org.arghyam.jalsoochak.message.dto.GlificDeliveryOutcome;
import org.arghyam.jalsoochak.message.dto.GlificMessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlificDeliveryStatusService}.
 *
 * <p>The Glific response shapes here are copied from real introspection and real query output against
 * {@code api.arghyam.glific.com} — see {@code mydocs/GLIFIC_API_CONTRACT.md}. Two of them matter most:
 * {@code errors} arrives as an <em>escaped JSON string</em> (so it needs a second parse), and its
 * {@code payload.destination} holds the recipient's phone number, which must never escape this
 * service.</p>
 *
 * <p>Phone numbers in fixtures use the {@code 91XXXXXXXXXX} shape but are not real, per CLAUDE.md.</p>
 */
@ExtendWith(MockitoExtension.class)
class GlificDeliveryStatusServiceTest {

    /** Not a real number. Present in the fixture precisely so a test can prove it never leaks. */
    private static final String FIXTURE_DESTINATION = "919999900001";

    /** The real payload shape Gupshup sends for a WhatsApp-undeliverable recipient. */
    private static final String UNDELIVERABLE_ERRORS = """
            {"version":2,"type":"message-event","timestamp":1787899103981,\
            "payload":{"type":"failed",\
            "payload":{"reason":"Message undeliverable, (#131026) Message Undeliverable.","code":131026},\
            "id":"034eQpfQs6K6e5qejugoEJ","gsId":"a075f0c0-dd7d-48ef-923c-805af416a59b",\
            "destination":"%s"},"app":"Arghyam"}""".formatted(FIXTURE_DESTINATION);

    /** The account-level failure: nothing to do with the recipient. */
    private static final String LOW_BALANCE_ERRORS = """
            {"version":2,"type":"message-event","timestamp":1782880142751,\
            "payload":{"type":"failed","payload":{"reason":"low balance","code":9999},\
            "id":"0d02808d-709c-4724-89bf-e092298696c6","destination":"%s"},"app":"Arghyam"}"""
            .formatted(FIXTURE_DESTINATION);

    @Mock
    private GlificGraphQLClient client;

    @InjectMocks
    private GlificDeliveryStatusService service;

    private final ObjectMapper mapper = new ObjectMapper();

    private final Instant from = Instant.parse("2026-08-27T00:30:00Z");
    private final Instant to = Instant.parse("2026-08-27T06:30:00Z");

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", mapper);
    }

    // ──────────────────────────── query construction ───────────────────────────

    @Nested
    class QueryConstruction {

        @Test
        @SuppressWarnings("unchecked")
        void fetchMessages_sendsTheConfirmedFilterAndOptsShape() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(emptyMessages());

            service.fetchMessages(from, to, "DELIVERED", "inserted_at", 250, 5);

            ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
            verify(client).execute(anyString(), vars.capture());

            Map<String, Object> filter = (Map<String, Object>) vars.getValue().get("filter");
            Map<String, Object> dateRange = (Map<String, Object>) filter.get("dateRange");
            assertThat(dateRange).containsEntry("column", "inserted_at")
                    .containsEntry("from", from.toString())
                    .containsEntry("to", to.toString());
            assertThat(filter).containsEntry("bspStatus", "DELIVERED");

            Map<String, Object> opts = (Map<String, Object>) vars.getValue().get("opts");
            assertThat(opts).containsEntry("limit", 250)
                    .containsEntry("offset", 0)
                    .containsEntry("order", "DESC")
                    .containsEntry("orderWith", "inserted_at");
        }

        @Test
        void countMessages_readsTheScalar() {
            ObjectNode data = mapper.createObjectNode();
            data.put("countMessages", 14208);
            when(client.execute(contains("countMessages"), anyMap())).thenReturn(data);

            assertThat(service.countMessages(from, to, "DELIVERED", "inserted_at")).isEqualTo(14208);
        }

        @Test
        void countMessages_returnsMinusOne_whenGlificDoesNotAnswerWithANumber() {
            when(client.execute(contains("countMessages"), anyMap())).thenReturn(mapper.createObjectNode());

            assertThat(service.countMessages(from, to, "DELIVERED", "inserted_at")).isEqualTo(-1);
        }
    }

    // ──────────────────────────────── pagination ───────────────────────────────

    @Nested
    class Pagination {

        @Test
        void stopsOnAShortPage() {
            when(client.execute(contains("messages"), anyMap()))
                    .thenReturn(messagesResponse(fullPage(2)))
                    .thenReturn(messagesResponse(fullPage(1)));

            List<GlificMessageStatus> result =
                    service.fetchMessages(from, to, "DELIVERED", "inserted_at", 2, 10);

            assertThat(result).hasSize(3);
            verify(client, times(2)).execute(anyString(), anyMap());
        }

        @Test
        void stopsOnAnEmptyPage() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(emptyMessages());

            assertThat(service.fetchMessages(from, to, "DELIVERED", "inserted_at", 2, 10)).isEmpty();
            verify(client, times(1)).execute(anyString(), anyMap());
        }

        /**
         * A truncated pass must never look like a complete one — it would under-report delivery while
         * appearing authoritative. The cap is honoured and (per the service) warned about.
         */
        @Test
        void honoursTheMaxPageCap() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(fullPage(2)));

            List<GlificMessageStatus> result =
                    service.fetchMessages(from, to, "DELIVERED", "inserted_at", 2, 3);

            assertThat(result).hasSize(6);
            verify(client, times(3)).execute(anyString(), anyMap());
        }
    }

    // ────────────────────────────── status mapping ─────────────────────────────

    @Nested
    class StatusMapping {

        @ParameterizedTest
        @CsvSource({
                "DELIVERED,       DELIVERED",
                "READ,            READ",
                "SEEN,            READ",
                "PLAYED,          READ",
                "ERROR,           DELIVERY_FAILED",
                "CONTACT_OPT_OUT, DELIVERY_FAILED",
                "ENQUEUED,        PENDING",
                "SENT,            PENDING",
                "REACHED,         PENDING",
                "RECEIVED,        IGNORED",
                "DELETED,         IGNORED",
        })
        void mapsEveryConfirmedEnumMember(String bspStatus, GlificDeliveryOutcome expected) {
            assertThat(GlificDeliveryOutcome.fromBspStatus(bspStatus)).isEqualTo(expected);
        }

        /** Glific may add members; a pass must not die on one it has never seen. */
        @Test
        void anUnknownStatusIsNotAnError() {
            assertThat(GlificDeliveryOutcome.fromBspStatus("SOME_FUTURE_STATE"))
                    .isEqualTo(GlificDeliveryOutcome.UNKNOWN_STATUS);
            assertThat(GlificDeliveryOutcome.fromBspStatus(null))
                    .isEqualTo(GlificDeliveryOutcome.UNKNOWN_STATUS);
        }

        /**
         * Glific's SENT means "Meta has it, not yet delivered" — a different fact from our own
         * {@code result=SENT}, which means "Glific accepted our API call". It must never be counted
         * as a success.
         */
        @Test
        void glificSentIsPendingNotDelivered() {
            assertThat(GlificDeliveryOutcome.fromBspStatus("SENT")).isEqualTo(GlificDeliveryOutcome.PENDING);
            assertThat(GlificDeliveryOutcome.fromBspStatus("SENT").isTerminal()).isFalse();
        }

        @Test
        void parsesAFullMessageNode() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(
                    message("241952654", "da3d8d62-fe89-4f63-ab20-730dac83a8a1", "DELIVERED",
                            880557, true, "OUTBOUND", "6530736", null)));

            GlificMessageStatus status =
                    service.fetchMessages(from, to, "DELIVERED", "inserted_at", 10, 1).get(0);

            assertThat(status.messageId()).isEqualTo("241952654");
            assertThat(status.bspMessageId()).isEqualTo("da3d8d62-fe89-4f63-ab20-730dac83a8a1");
            assertThat(status.bspStatus()).isEqualTo("DELIVERED");
            assertThat(status.templateId()).isEqualTo(880557);
            assertThat(status.hsm()).isTrue();
            assertThat(status.flow()).isEqualTo("OUTBOUND");
            assertThat(status.receiverContactId()).isEqualTo(6530736L);
            assertThat(status.outcome()).isEqualTo(GlificDeliveryOutcome.DELIVERED);
            assertThat(status.isOutboundHsm()).isTrue();
        }

        /**
         * An inbound message's {@code receiver} is our own org contact, not an officer, so counting one
         * would attribute a status to entirely the wrong person. It must be recognisable as not-ours.
         */
        @Test
        void anInboundMessageIsNotAnOutboundHsm() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(
                    message("241962444", "wamid.HBgMOTE4NjM4", "DELIVERED",
                            null, false, "INBOUND", "2239259", null)));

            GlificMessageStatus status =
                    service.fetchMessages(from, to, "DELIVERED", "inserted_at", 10, 1).get(0);

            assertThat(status.isOutboundHsm()).isFalse();
            assertThat(status.templateId()).isNull();
        }
    }

    // ─────────────────────────── errors payload + PII ──────────────────────────

    @Nested
    class ErrorsPayload {

        @Test
        void extractsCodeAndReasonFromTheDoublyNestedEscapedJson() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(
                    message("241952232", "gs-1", "ERROR", 880557, true, "OUTBOUND", "6629592",
                            UNDELIVERABLE_ERRORS)));

            GlificMessageStatus status =
                    service.fetchMessages(from, to, "ERROR", "inserted_at", 10, 1).get(0);

            assertThat(status.outcome()).isEqualTo(GlificDeliveryOutcome.DELIVERY_FAILED);
            assertThat(status.errorCode()).isEqualTo("131026");
            assertThat(status.errorReason()).contains("Message Undeliverable");
            assertThat(status.failureKey()).isEqualTo("131026");
        }

        /**
         * The whole point of the redaction: Gupshup puts the recipient's raw number in
         * {@code payload.destination}, and nothing derived from that payload may carry it forward.
         */
        @Test
        void neverCarriesTheRecipientPhoneNumberOutOfTheErrorsPayload() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(
                    message("241952232", "gs-1", "ERROR", 880557, true, "OUTBOUND", "6629592",
                            UNDELIVERABLE_ERRORS)));

            GlificMessageStatus status =
                    service.fetchMessages(from, to, "ERROR", "inserted_at", 10, 1).get(0);

            assertThat(status.toString()).doesNotContain(FIXTURE_DESTINATION);
            assertThat(status.errorReason()).doesNotContain(FIXTURE_DESTINATION);
            assertThat(status.errorCode()).doesNotContain(FIXTURE_DESTINATION);
        }

        /** A number embedded in the reason text itself is masked too, not just the destination field. */
        @Test
        void redactsAPhoneNumberEmbeddedInTheReasonText() {
            String errors = """
                    {"payload":{"payload":{"reason":"No WhatsApp account for 919999900002","code":131026}}}""";
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(
                    message("1", "gs-1", "ERROR", 880557, true, "OUTBOUND", "1", errors)));

            GlificMessageStatus status =
                    service.fetchMessages(from, to, "ERROR", "inserted_at", 10, 1).get(0);

            assertThat(status.errorReason()).doesNotContain("919999900002").contains("0002");
        }

        @Test
        void readsTheAccountLevelLowBalanceCode() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(
                    message("233212612", "gs-2", "ERROR", null, true, "OUTBOUND", "6275488",
                            LOW_BALANCE_ERRORS)));

            GlificMessageStatus status =
                    service.fetchMessages(from, to, "ERROR", "inserted_at", 10, 1).get(0);

            assertThat(status.errorCode()).isEqualTo("9999");
            assertThat(status.errorReason()).isEqualTo("low balance");
        }

        /** CONTACT_OPT_OUT carries its meaning in the status; Glific need not supply an errors blob. */
        @Test
        void synthesisesAReasonForContactOptOut() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(
                    message("2", "gs-3", "CONTACT_OPT_OUT", 880557, true, "OUTBOUND", "77", null)));

            GlificMessageStatus status =
                    service.fetchMessages(from, to, "CONTACT_OPT_OUT", "inserted_at", 10, 1).get(0);

            assertThat(status.outcome()).isEqualTo(GlificDeliveryOutcome.DELIVERY_FAILED);
            assertThat(status.errorCode()).isEqualTo("CONTACT_OPT_OUT");
            assertThat(status.errorReason()).isEqualTo("contact opted out");
        }

        @Test
        void survivesAnUnparseableErrorsPayload() {
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(
                    message("3", "gs-4", "ERROR", 880557, true, "OUTBOUND", "78", "not json at all")));

            GlificMessageStatus status =
                    service.fetchMessages(from, to, "ERROR", "inserted_at", 10, 1).get(0);

            assertThat(status.outcome()).isEqualTo(GlificDeliveryOutcome.DELIVERY_FAILED);
            assertThat(status.errorCode()).isNull();
            assertThat(status.failureKey()).isEqualTo("ERROR");
        }

        /** Defensive: tolerate an already-parsed object if the scalar's serialisation ever differs. */
        @Test
        void acceptsAnAlreadyParsedErrorsObject() throws Exception {
            ObjectNode msg = baseMessage("4", "gs-5", "ERROR", 880557, true, "OUTBOUND", "79");
            msg.set("errors", mapper.readTree(UNDELIVERABLE_ERRORS));
            when(client.execute(contains("messages"), anyMap())).thenReturn(messagesResponse(msg));

            GlificMessageStatus status =
                    service.fetchMessages(from, to, "ERROR", "inserted_at", 10, 1).get(0);

            assertThat(status.errorCode()).isEqualTo("131026");
        }
    }

    // ─────────────────────────────── fixtures ──────────────────────────────────

    private JsonNode emptyMessages() {
        ObjectNode data = mapper.createObjectNode();
        data.putArray("messages");
        return data;
    }

    private JsonNode messagesResponse(ObjectNode... messages) {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode array = data.putArray("messages");
        for (ObjectNode m : messages) {
            array.add(m);
        }
        return data;
    }

    private ObjectNode[] fullPage(int size) {
        ObjectNode[] page = new ObjectNode[size];
        for (int i = 0; i < size; i++) {
            page[i] = baseMessage(String.valueOf(i), "gs-" + i, "DELIVERED", 880557, true, "OUTBOUND", "100");
        }
        return page;
    }

    private ObjectNode message(String id, String bspMessageId, String bspStatus, Integer templateId,
                               boolean hsm, String flow, String receiverId, String errorsJson) {
        ObjectNode node = baseMessage(id, bspMessageId, bspStatus, templateId, hsm, flow, receiverId);
        if (errorsJson != null) {
            // Glific returns `errors` as an escaped JSON *string*, not an object — reproduced exactly.
            node.put("errors", errorsJson);
        }
        return node;
    }

    private ObjectNode baseMessage(String id, String bspMessageId, String bspStatus, Integer templateId,
                                   boolean hsm, String flow, String receiverId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        node.put("bspMessageId", bspMessageId);
        node.put("bspStatus", bspStatus);
        if (templateId == null) {
            node.putNull("templateId");
        } else {
            node.put("templateId", templateId);
        }
        node.put("isHsm", hsm);
        node.put("flow", flow);
        node.put("insertedAt", "2026-08-27T06:39:17.245676Z");
        node.put("updatedAt", "2026-08-27T06:41:02.100000Z");
        node.putObject("receiver").put("id", receiverId);
        return node;
    }
}
