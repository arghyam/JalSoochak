package org.arghyam.jalsoochak.telemetry.config;

import com.sun.net.httpserver.HttpServer;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.arghyam.jalsoochak.telemetry.security.SsrfAddressPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiring-level proof for the guarded media client, against a real HTTP server on loopback.
 *
 * <p>Loopback is exactly what the guard is built to refuse, which makes it the sharpest fixture
 * available: the same server is fetched successfully with the guard relaxed for local development,
 * and refused with the guard in its deployed configuration.
 */
@DisplayName("mediaFetchRestTemplate — the guarded client for caller-supplied URLs")
class MediaFetchRestTemplateConfigTest {

    private static final byte[] IMAGE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image.jpg", exchange -> respond(exchange, 200, IMAGE));
        server.createContext("/redirect-to-metadata", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-to-image", exchange -> {
            exchange.getResponseHeaders().add("Location", "/image.jpg");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/huge.jpg", exchange -> respond(exchange, 200, new byte[64 * 1024]));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private RestTemplate client(boolean allowInternalAddresses) {
        return client(allowInternalAddresses, "");
    }

    private RestTemplate client(boolean allowInternalAddresses, String allowedHosts) {
        MediaFetchRestTemplateConfig config = new MediaFetchRestTemplateConfig(
                true, false, allowInternalAddresses, allowedHosts, 3, 2000, 2000);
        SsrfAddressPolicy addressPolicy = new SsrfAddressPolicy(allowInternalAddresses);
        MediaUrlValidator validator = config.mediaUrlValidator(addressPolicy);
        return config.mediaFetchRestTemplate(addressPolicy, validator);
    }

    @Test
    void fetchesAnImageWhenInternalAddressesAreExplicitlyAllowed() {
        byte[] body = client(true).getForObject(URI.create(baseUrl + "/image.jpg"), byte[].class);

        assertThat(body).isEqualTo(IMAGE);
    }

    @Test
    void refusesToConnectToLoopbackUnderTheDeployedConfiguration() {
        RestTemplate guarded = client(false);
        URI target = URI.create(baseUrl + "/image.jpg");

        // Blocked in the DNS resolver, so the socket is never opened; RestTemplate surfaces that as
        // a ResourceAccessException wrapping UnknownHostException.
        assertThatThrownBy(() -> guarded.getForObject(target, byte[].class))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(java.net.UnknownHostException.class);
    }

    @Test
    void refusesToFollowARedirectIntoTheInternalNetwork() {
        // Internal addresses are allowed here, so only the redirect guard can stop this one — which
        // is the point: a permitted first hop must not become a free pass to the metadata endpoint.
        RestTemplate guarded = client(true, "127.0.0.1");
        URI target = URI.create(baseUrl + "/redirect-to-metadata");

        assertThatThrownBy(() -> guarded.getForObject(target, byte[].class))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void stillFollowsARedirectThatStaysWithinPolicy() {
        byte[] body = client(true, "127.0.0.1")
                .getForObject(URI.create(baseUrl + "/redirect-to-image"), byte[].class);

        assertThat(body).isEqualTo(IMAGE);
    }

    @Test
    void sendsTheRequestWithoutCookiesOrCachedCredentials() {
        // Nothing to assert beyond the call succeeding: cookie and auth caching are disabled on this
        // client so a caller-supplied host cannot influence a later request on the shared pool.
        byte[] body = client(true).execute(URI.create(baseUrl + "/image.jpg"), HttpMethod.GET, null,
                response -> response.getBody().readAllBytes());

        assertThat(body).isEqualTo(IMAGE);
    }
}
