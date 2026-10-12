package org.arghyam.jalsoochak.telemetry.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The HTTP client for the ELM OCR provider.
 *
 * <p>It is separate from the shared {@link RestTemplateConfig#restTemplate()} because an ELM read
 * takes 20–30 seconds and up to about 100. The shared client's read timeout is sized for BFM's OCR
 * provider, the WhatsApp provider and object storage, and raising it would let any of those hang a
 * thread for as long.
 */
@Configuration
public class ElmOcrRestTemplateConfig {

    private static final int CONNECTION_REQUEST_TIMEOUT_MS = 2000;
    private static final int MAX_TOTAL_CONNECTIONS = 50;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 50;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public ElmOcrRestTemplateConfig(
            @Value("${ocr.elm.http.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${ocr.elm.http.read-timeout-ms:120000}") int readTimeoutMs
    ) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Bean
    public RestTemplate elmOcrRestTemplate() {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(MAX_TOTAL_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(CONNECTION_REQUEST_TIMEOUT_MS))
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .disableCookieManagement()
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
