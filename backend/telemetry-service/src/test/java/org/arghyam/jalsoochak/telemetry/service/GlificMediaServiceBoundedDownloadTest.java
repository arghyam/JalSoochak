package org.arghyam.jalsoochak.telemetry.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.arghyam.jalsoochak.telemetry.config.MediaFetchRestTemplateConfig;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.arghyam.jalsoochak.telemetry.security.SsrfAddressPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The byte ceiling on a fetch from a caller-supplied URL, exercised end to end against a real
 * server: a URL the caller chose can point at a response of any size, and buffering it whole would
 * move the limit onto the heap.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GlificMediaService — bounded download from a caller-supplied URL")
class GlificMediaServiceBoundedDownloadTest {

    private static final int LIMIT_BYTES = 4096;

    @Mock
    private MinioService minioService;
    @Mock
    private RestTemplate sharedRestTemplate;

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger redirectRequests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        redirectRequests.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/small.jpg", exchange -> respond(exchange, new byte[LIMIT_BYTES / 2], true));
        server.createContext("/declared-huge.jpg", exchange -> respond(exchange, new byte[LIMIT_BYTES * 4], true));
        // Chunked: no Content-Length to check up front, so only the read itself can stop it.
        server.createContext("/chunked-huge.jpg", exchange -> respond(exchange, new byte[LIMIT_BYTES * 4], false));
        // Same server, but under a host name the allowlist does not carry — the shape of a redirect
        // that steps outside the allowlist, without needing a second listener.
        server.createContext("/redirect-off-allowlist.jpg", exchange -> {
            redirectRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Location",
                    "http://localhost:" + exchange.getLocalAddress().getPort() + "/small.jpg");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, byte[] body, boolean declareLength) throws IOException {
        exchange.sendResponseHeaders(200, declareLength ? body.length : 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private GlificMediaService service() {
        return service(LIMIT_BYTES);
    }

    private GlificMediaService service(long maxBytes) {
        // Internal addresses are allowed so the loopback fixture is reachable; the size ceiling under
        // test is independent of the address policy.
        MediaFetchRestTemplateConfig config = new MediaFetchRestTemplateConfig(
                true, false, true, "", 3, 2000, 2000);
        SsrfAddressPolicy addressPolicy = new SsrfAddressPolicy(true);
        MediaUrlValidator validator = config.mediaUrlValidator(addressPolicy);
        return new GlificMediaService(minioService, sharedRestTemplate,
                config.mediaFetchRestTemplate(addressPolicy, validator), validator,
                "https://api.glific.org/v1/media", 1, 0L, 0L, 0L, maxBytes, "token");
    }

    @Test
    void downloadsAnImageWithinTheLimit() throws IOException {
        byte[] image = service().downloadImage(null, baseUrl + "/small.jpg");

        assertThat(image).hasSize(LIMIT_BYTES / 2);
    }

    @Test
    void refusesAResponseThatDeclaresItselfTooLarge() {
        GlificMediaService service = service();

        assertThatThrownBy(() -> service.downloadImage(null, baseUrl + "/declared-huge.jpg"))
                .isInstanceOf(GlificMediaService.MediaTooLargeException.class);
    }

    @Test
    void refusesAResponseThatOnlyTurnsOutToBeTooLargeWhileReading() {
        GlificMediaService service = service();

        // A chunked response declares no length, so the ceiling has to hold during the read itself.
        assertThatThrownBy(() -> service.downloadImage(null, baseUrl + "/chunked-huge.jpg"))
                .isInstanceOf(GlificMediaService.MediaTooLargeException.class);
    }

    @Test
    void refusesToStartWhenTheConfiguredCeilingWouldDisableIt() {
        // A non-positive value used to mean "no limit", so one bad property silently reopened the
        // unbounded read this ceiling exists to prevent. It now fails the service's construction.
        assertThatThrownBy(() -> service(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("media-download.max-bytes");
        assertThatThrownBy(() -> service(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotRetryARedirectTheUrlPolicyRefused() {
        // The refusal reaches the retry loop wrapped as a ResourceAccessException, which on its face
        // looks like a transient network fault. It is not: the policy's verdict is about where the
        // URL points, so three attempts must still cost the origin exactly one request.
        GlificMediaService service = allowlistedService();

        assertThatThrownBy(() -> service.downloadImage(null, baseUrl + "/redirect-off-allowlist.jpg"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("non-retriable");

        assertThat(redirectRequests).hasValue(1);
    }

    /** The same fixture with an allowlist, so a redirect can step outside it and be refused. */
    private GlificMediaService allowlistedService() {
        MediaFetchRestTemplateConfig config = new MediaFetchRestTemplateConfig(
                true, false, true, "127.0.0.1", 3, 2000, 2000);
        SsrfAddressPolicy addressPolicy = new SsrfAddressPolicy(true);
        MediaUrlValidator validator = config.mediaUrlValidator(addressPolicy);
        return new GlificMediaService(minioService, sharedRestTemplate,
                config.mediaFetchRestTemplate(addressPolicy, validator), validator,
                "https://api.glific.org/v1/media", 3, 0L, 0L, 0L, LIMIT_BYTES, "token");
    }
}
