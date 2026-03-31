package org.arghyam.jalsoochak.message.channel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.arghyam.jalsoochak.message.dto.NotificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebhookChannel}.
 *
 * <p>Uses WireMock to stub the HTTP server. Verifies that successful POSTs
 * return {@code true}, HTTP errors return {@code false}, missing URLs
 * return {@code false}, and the recipient URL overrides the default.</p>
 */
class WebhookChannelTest {

    private WireMockServer wireMock;
    private WebhookChannel channel;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();

        channel = new WebhookChannel(WebClient.builder());
        ReflectionTestUtils.setField(channel, "defaultWebhookUrl", wireMock.baseUrl() + "/hook");
        ReflectionTestUtils.setField(channel, "webhookSecret", "test-secret");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void send_returnsTrue_onSuccessfulPost() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse().withStatus(200)));

        boolean result = channel.send(notification(null, "Subject", "Body"));

        assertThat(result).isTrue();
        wireMock.verify(postRequestedFor(urlEqualTo("/hook")));
    }

    @Test
    void send_returnsFalse_onHttpError() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse().withStatus(500)));

        boolean result = channel.send(notification(null, "Subject", "Body"));

        assertThat(result).isFalse();
    }

    @Test
    void send_returnsFalse_whenNoUrlConfigured() {
        ReflectionTestUtils.setField(channel, "defaultWebhookUrl", "");

        boolean result = channel.send(notification(null, "Subject", "Body"));

        assertThat(result).isFalse();
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void send_usesRecipientUrl_whenProvided() {
        wireMock.stubFor(post(urlEqualTo("/custom"))
                .willReturn(aResponse().withStatus(200)));

        boolean result = channel.send(notification(wireMock.baseUrl() + "/custom", "S", "B"));

        assertThat(result).isTrue();
        wireMock.verify(postRequestedFor(urlEqualTo("/custom")));
    }

    @Test
    void send_usesDefaultUrl_whenRecipientIsBlank() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse().withStatus(200)));

        boolean result = channel.send(notification("  ", "S", "B"));

        assertThat(result).isTrue();
        wireMock.verify(postRequestedFor(urlEqualTo("/hook")));
    }

    @Test
    void send_sendsWebhookSecretHeader() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse().withStatus(200)));

        channel.send(notification(null, "S", "B"));

        wireMock.verify(postRequestedFor(urlEqualTo("/hook"))
                .withHeader("X-Webhook-Secret", equalTo("test-secret")));
    }

    @Test
    void channelType_returnsWebhook() {
        assertThat(channel.channelType()).isEqualTo("WEBHOOK");
    }

    private static NotificationRequest notification(String recipient, String subject, String body) {
        NotificationRequest req = new NotificationRequest();
        req.setRecipient(recipient);
        req.setSubject(subject);
        req.setBody(body);
        return req;
    }
}
