package org.arghyam.jalsoochak.telemetry.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ELM OCR client's read timeout, checked against a real HTTP server on loopback. */
@DisplayName("elmOcrRestTemplate — the slow-read client for the ELM OCR provider")
class ElmOcrRestTemplateConfigTest {

    private static final long SLOW_RESPONSE_MS = 600;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", ElmOcrRestTemplateConfigTest::respondSlowly);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respondSlowly(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(SLOW_RESPONSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void waitsForAResponseWithinTheReadTimeout() {
        RestTemplate client = new ElmOcrRestTemplateConfig(2000, 5000).elmOcrRestTemplate();

        assertThat(client.getForObject(baseUrl + "/slow", String.class)).isEqualTo("{}");
    }

    @Test
    void givesUpOnAResponseSlowerThanTheReadTimeout() {
        RestTemplate client = new ElmOcrRestTemplateConfig(2000, 100).elmOcrRestTemplate();

        assertThatThrownBy(() -> client.getForObject(baseUrl + "/slow", String.class))
                .isInstanceOf(ResourceAccessException.class);
    }
}
