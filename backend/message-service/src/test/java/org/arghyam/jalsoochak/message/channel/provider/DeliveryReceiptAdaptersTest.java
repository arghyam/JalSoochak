package org.arghyam.jalsoochak.message.channel.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two pushed-report adapters: each refuses a request it cannot authenticate, and turns an authentic
 * one into provider-neutral reports that carry no address or message text.
 */
class DeliveryReceiptAdaptersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class SendGrid {

        private static KeyPair keys;
        private static KeyPair otherKeys;

        @BeforeAll
        static void generateKeys() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            keys = generator.generateKeyPair();
            otherKeys = generator.generateKeyPair();
        }

        private static final String EVENTS = """
                [{"email":"someone@example.org","timestamp":1759658400,"event":"delivered",
                  "sg_message_id":"W86EgYT6SQKk0lRflfLRsA.filterdrecv-1","ledger_ref":"tenant_mp:7d0f6c0a-1111-2222-3333-444455556666"},
                 {"email":"someone@example.org","timestamp":1759658460,"event":"bounce","type":"bounce",
                  "status":"5.1.1","bounce_classification":"Invalid Address",
                  "reason":"550 5.1.1 <someone@example.org>: Recipient address rejected; call 919876500001",
                  "sg_message_id":"X1.filterdrecv-2"},
                 {"email":"someone@example.org","timestamp":1759658470,"event":"spamreport","sg_message_id":"X2.f"},
                 {"email":"someone@example.org","timestamp":1759658480,"event":"open","sg_message_id":"X3.f"}]
                """;

        private SendGridDeliveryReceiptAdapter adapter(String mode) {
            String key = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
            return new SendGridDeliveryReceiptAdapter(MAPPER, key, mode);
        }

        private static DeliveryReceiptRequest signed(KeyPair signer, String body) throws Exception {
            String timestamp = "1759658500";
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(signer.getPrivate());
            signature.update(timestamp.getBytes(StandardCharsets.UTF_8));
            signature.update(body.getBytes(StandardCharsets.UTF_8));
            return new DeliveryReceiptRequest(Map.of(
                    "x-twilio-email-event-webhook-signature", Base64.getEncoder().encodeToString(signature.sign()),
                    "x-twilio-email-event-webhook-timestamp", timestamp),
                    body.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        @Test
        void parsesASignedBatch() throws Exception {
            List<DeliveryReceipt> receipts = adapter("ENFORCE").parseAndVerify(signed(keys, EVENTS));

            assertThat(receipts).hasSize(3);
            DeliveryReceipt delivered = receipts.get(0);
            assertThat(delivered.providerId()).isEqualTo("sendgrid");
            assertThat(delivered.providerMessageId()).isEqualTo("W86EgYT6SQKk0lRflfLRsA");
            assertThat(delivered.trackingRef()).isEqualTo("tenant_mp:7d0f6c0a-1111-2222-3333-444455556666");
            assertThat(delivered.state()).isEqualTo(DeliveryState.DELIVERED);
            assertThat(delivered.occurredAt()).isEqualTo(Instant.ofEpochSecond(1759658400));

            DeliveryReceipt bounce = receipts.get(1);
            assertThat(bounce.state()).isEqualTo(DeliveryState.FAILED);
            assertThat(bounce.errorCode()).isEqualTo("Invalid Address");
            assertThat(bounce.errorReason()).doesNotContain("someone@example.org").doesNotContain("919876500001");

            assertThat(receipts.get(2).state()).as("an open is a read").isEqualTo(DeliveryState.READ);
            assertThat(receipts.toString()).doesNotContain("someone@example.org");
        }

        @Test
        void refusesARequestSignedWithAnotherKey() throws Exception {
            DeliveryReceiptRequest forged = signed(otherKeys, EVENTS);

            assertThatThrownBy(() -> adapter("ENFORCE").parseAndVerify(forged))
                    .isInstanceOf(ReceiptRejectedException.class);
        }

        @Test
        void refusesATamperedBody() throws Exception {
            DeliveryReceiptRequest original = signed(keys, EVENTS);
            DeliveryReceiptRequest tampered = new DeliveryReceiptRequest(original.headers(),
                    EVENTS.replace("delivered", "bounce").getBytes(StandardCharsets.UTF_8), Map.of());

            assertThatThrownBy(() -> adapter("ENFORCE").parseAndVerify(tampered))
                    .isInstanceOf(ReceiptRejectedException.class);
        }

        @Test
        void refusesEverythingWhenNoKeyIsConfigured() {
            SendGridDeliveryReceiptAdapter unconfigured = new SendGridDeliveryReceiptAdapter(MAPPER, "", "ENFORCE");

            assertThatThrownBy(() -> unconfigured.parseAndVerify(new DeliveryReceiptRequest(Map.of(),
                    "[]".getBytes(StandardCharsets.UTF_8), Map.of())))
                    .isInstanceOf(ReceiptRejectedException.class);
        }

        @Test
        void auditModeAcceptsAnUnsignedRequest() {
            assertThat(adapter("AUDIT").parseAndVerify(new DeliveryReceiptRequest(Map.of(),
                    EVENTS.getBytes(StandardCharsets.UTF_8), Map.of()))).hasSize(3);
        }

        @Test
        void anAuthenticBodyThatIsNotAnArrayIsUnreadable() throws Exception {
            assertThatThrownBy(() -> adapter("ENFORCE").parseAndVerify(signed(keys, "{}")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aMalformedKeyFailsAtStartup() {
            assertThatThrownBy(() -> new SendGridDeliveryReceiptAdapter(MAPPER, "not-a-key", "ENFORCE"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class Sms {

        private static final String TOKEN = "s3cret-token";
        private static final String UUID = "574cd18e-7ab7-4785-a388-d8443eba59b8";

        private SmsCountryDeliveryReceiptAdapter adapter(String mode) throws Exception {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
            return new SmsCountryDeliveryReceiptAdapter(MAPPER, hash, mode);
        }

        @Test
        void readsAJsonReport_withTheRefFromTheUrl() throws Exception {
            String body = """
                    {"MessageUUID":"%s","Number":"91XXXXXXXXXX","Text":"Your OTP is 000000",
                     "Status":"Delivered","StatusTime":"Sep 29 2026  8:02PM","Cost":"0.3 INR"}
                    """.formatted(UUID);

            List<DeliveryReceipt> receipts = adapter("ENFORCE").parseAndVerify(new DeliveryReceiptRequest(Map.of(),
                    body.getBytes(StandardCharsets.UTF_8), Map.of("token", TOKEN, "ref", "tenant_mp:abc")));

            assertThat(receipts).singleElement().satisfies(r -> {
                assertThat(r.providerId()).isEqualTo("smscountry");
                assertThat(r.providerMessageId()).isEqualTo(UUID);
                assertThat(r.trackingRef()).isEqualTo("tenant_mp:abc");
                assertThat(r.state()).isEqualTo(DeliveryState.DELIVERED);
                assertThat(r.toString()).doesNotContain("000000").doesNotContain("91XXXXXXXXXX");
            });
        }

        @Test
        void readsAFormReport() throws Exception {
            String body = "MessageUUID=" + UUID + "&Status=Failed&Number=91XXXXXXXXXX";

            assertThat(adapter("ENFORCE").parseAndVerify(new DeliveryReceiptRequest(Map.of(),
                    body.getBytes(StandardCharsets.UTF_8), Map.of("token", TOKEN))))
                    .singleElement().extracting(DeliveryReceipt::state).isEqualTo(DeliveryState.FAILED);
        }

        @Test
        void readsAReportCarriedInTheQueryString() throws Exception {
            assertThat(adapter("ENFORCE").parseAndVerify(new DeliveryReceiptRequest(Map.of(), new byte[0],
                    Map.of("token", TOKEN, "MessageUUID", UUID, "Status", "Delivered"))))
                    .singleElement().extracting(DeliveryReceipt::state).isEqualTo(DeliveryState.DELIVERED);
        }

        @Test
        void refusesAWrongOrMissingToken() throws Exception {
            SmsCountryDeliveryReceiptAdapter enforcing = adapter("ENFORCE");

            assertThatThrownBy(() -> enforcing.parseAndVerify(new DeliveryReceiptRequest(Map.of(), new byte[0],
                    Map.of("token", "guess")))).isInstanceOf(ReceiptRejectedException.class);
            assertThatThrownBy(() -> enforcing.parseAndVerify(new DeliveryReceiptRequest(Map.of(), new byte[0],
                    Map.of()))).isInstanceOf(ReceiptRejectedException.class);
        }

        @Test
        void aReportWithoutAUuidYieldsNothing() throws Exception {
            assertThat(adapter("ENFORCE").parseAndVerify(new DeliveryReceiptRequest(Map.of(),
                    "{\"Status\":\"Delivered\"}".getBytes(StandardCharsets.UTF_8), Map.of("token", TOKEN)))).isEmpty();
        }
    }
}
