package org.arghyam.jalsoochak.message.channel.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link SendGridMailSender} hands the delivery ledger: the {@code X-Message-Id} of an accepted
 * send, our tracking reference in {@code custom_args}, and the Email Activity lookup.
 */
class SendGridMailSenderLedgerTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SendGridMailSender sender(boolean lookup) {
        return new SendGridMailSender(new SendGridSettings(wireMock.baseUrl(), "SG.test-key", "noreply@example.org",
                "Jal", "https://logo", new SendGridSettings.Templates("d-pw", "d-re", "d-def", "d-su", "d-sa")),
                WebClient.builder(), lookup);
    }

    private static MailRequest request(String trackingRef) {
        return new MailRequest("to@example.org", MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", "https://r", "expiry_minutes", 30), trackingRef);
    }

    @Test
    void returnsTheMessageIdSendGridAssigned() {
        wireMock.stubFor(post(urlEqualTo("/v3/mail/send"))
                .willReturn(aResponse().withStatus(202).withHeader("X-Message-Id", "W86EgYT6SQKk0lRflfLRsA")));

        ProviderAcceptance acceptance = sender(false).send(request(null));

        assertThat(acceptance.providerMessageId()).isEqualTo("W86EgYT6SQKk0lRflfLRsA");
        assertThat(acceptance.providerStatus()).isEqualTo("202");
        assertThat(acceptance.isTrackable()).isTrue();
        assertThat(sender(false).providerId()).isEqualTo("sendgrid");
    }

    @Test
    void anAcceptanceWithoutTheHeaderIsUntracked() {
        wireMock.stubFor(post(urlEqualTo("/v3/mail/send")).willReturn(aResponse().withStatus(202)));

        assertThat(sender(false).send(request(null)).isTrackable()).isFalse();
    }

    @Test
    void sendsTheTrackingRefAsACustomArg_andNothingWhenThereIsNone() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/v3/mail/send")).willReturn(aResponse().withStatus(202)));

        sender(false).send(request("tenant_mp:7d0f6c0a-1111-2222-3333-444455556666"));
        sender(false).send(request(null));

        List<JsonNode> bodies = wireMock.findAll(postRequestedFor(urlEqualTo("/v3/mail/send"))).stream()
                .map(r -> readTree(r.getBodyAsString())).toList();
        assertThat(bodies.get(0).at("/personalizations/0/custom_args/ledger_ref").asText())
                .isEqualTo("tenant_mp:7d0f6c0a-1111-2222-3333-444455556666");
        assertThat(bodies.get(1).at("/personalizations/0").has("custom_args")).isFalse();
    }

    @Test
    void looksNothingUpUnlessEnabled() {
        assertThat(sender(false).supportsStatusLookup()).isFalse();
        assertThat(sender(false).lookupStatuses(List.of("abc"), Instant.now(), Instant.now())).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void mapsTheEmailActivityStatus() {
        wireMock.stubFor(get(urlPathEqualTo("/v3/messages")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"messages":[{"msg_id":"abc.filter","status":"delivered","opens_count":2,
                          "to_email":"someone@example.org","last_event_time":"2026-10-05T10:00:00Z"}]}
                        """)));

        List<DeliveryReceipt> receipts = sender(true).lookupStatuses(List.of("abc"), Instant.now(), Instant.now());

        assertThat(receipts).singleElement().satisfies(r -> {
            assertThat(r.state()).isEqualTo(DeliveryState.READ);
            assertThat(r.providerMessageId()).isEqualTo("abc");
            assertThat(r.occurredAt()).isEqualTo(Instant.parse("2026-10-05T10:00:00Z"));
            assertThat(r.toString()).doesNotContain("someone@example.org");
        });
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
