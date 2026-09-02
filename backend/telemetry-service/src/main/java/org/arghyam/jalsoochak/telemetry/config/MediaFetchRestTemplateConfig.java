package org.arghyam.jalsoochak.telemetry.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.arghyam.jalsoochak.telemetry.security.HostResolver;
import org.arghyam.jalsoochak.telemetry.security.MediaFetchRedirectStrategy;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.arghyam.jalsoochak.telemetry.security.SsrfAddressPolicy;
import org.arghyam.jalsoochak.telemetry.security.SsrfGuardDnsResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The HTTP client used for one thing only: fetching a meter image from a URL supplied by the caller
 * of the Glific webhook.
 *
 * <p>It is deliberately a separate client from the shared {@link RestTemplateConfig#restTemplate()}.
 * The shared one talks to FlowVision, Glific and MinIO, any of which may legitimately sit on an
 * internal address depending on the environment; putting an address guard on it would break those
 * calls. Isolating the guard here means the only request path that changes behaviour is the one that
 * was actually exposed.
 */
@Configuration
public class MediaFetchRestTemplateConfig {

    private static final int CONNECTION_REQUEST_TIMEOUT_MS = 2000;
    private static final int MAX_TOTAL_CONNECTIONS = 50;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 20;

    private final boolean guardEnabled;
    private final boolean requireHttps;
    private final boolean allowInternalAddresses;
    private final String allowedHosts;
    private final int maxRedirects;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public MediaFetchRestTemplateConfig(
            @Value("${media-download.ssrf-guard.enabled:true}") boolean guardEnabled,
            @Value("${media-download.ssrf-guard.require-https:false}") boolean requireHttps,
            @Value("${media-download.ssrf-guard.allow-internal-addresses:false}") boolean allowInternalAddresses,
            @Value("${media-download.ssrf-guard.allowed-hosts:}") String allowedHosts,
            @Value("${media-download.ssrf-guard.max-redirects:3}") int maxRedirects,
            @Value("${media-download.http.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${media-download.http.read-timeout-ms:10000}") int readTimeoutMs
    ) {
        this.guardEnabled = guardEnabled;
        this.requireHttps = requireHttps;
        this.allowInternalAddresses = allowInternalAddresses;
        this.allowedHosts = allowedHosts;
        this.maxRedirects = Math.max(0, maxRedirects);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Bean
    public SsrfAddressPolicy mediaFetchAddressPolicy() {
        return new SsrfAddressPolicy(allowInternalAddresses);
    }

    @Bean
    public MediaUrlValidator mediaUrlValidator(SsrfAddressPolicy mediaFetchAddressPolicy) {
        return new MediaUrlValidator(
                guardEnabled,
                requireHttps,
                MediaUrlValidator.parseAllowedHosts(allowedHosts),
                mediaFetchAddressPolicy,
                HostResolver.SYSTEM
        );
    }

    @Bean
    public RestTemplate mediaFetchRestTemplate(SsrfAddressPolicy mediaFetchAddressPolicy,
                                               MediaUrlValidator mediaUrlValidator) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(MAX_TOTAL_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .setDnsResolver(new SsrfGuardDnsResolver(SystemDefaultDnsResolver.INSTANCE, mediaFetchAddressPolicy))
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(CONNECTION_REQUEST_TIMEOUT_MS))
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .setMaxRedirects(maxRedirects)
                .setRedirectsEnabled(maxRedirects > 0)
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setRedirectStrategy(new MediaFetchRedirectStrategy(mediaUrlValidator))
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .disableCookieManagement()
                .disableAuthCaching()
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
