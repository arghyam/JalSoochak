package org.arghyam.jalsoochak.message.config;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.storage.ObjectStorageService;
import org.arghyam.jalsoochak.message.storage.S3CompatibleStorageService;
import org.arghyam.jalsoochak.message.util.PublicUrlValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Wires the S3-compatible {@link ObjectStorageService} when {@code storage.enabled=true}.
 *
 * <p>There is deliberately no no-op fallback for a disabled store: this service has no degraded
 * mode for storage, so anything that needs {@link ObjectStorageService} fails at startup rather
 * than every upload silently going nowhere.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@Slf4j
public class StorageConfig {

    /**
     * Talks to {@code storage.endpoint} with path-style access, which most S3-compatible stores other
     * than AWS require. The endpoint is required: there is no implicit AWS default, so a deployment
     * that misses it stops at startup instead of uploading to AWS.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public S3Client s3Client(StorageProperties props) {
        validateEndpoint(props);
        validateCredentials(props);
        log.info("[Storage] Using endpoint: {} (path-style enabled)", sanitizeEndpoint(props.getEndpoint()));
        return S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()))
                .endpointOverride(URI.create(props.getEndpoint()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public ObjectStorageService objectStorageService(S3Client s3Client, StorageProperties props) {
        validatePublicBaseUrl(props.getPublicBaseUrl());
        warnIfPublicBaseUrlIsNotPublic(props.getPublicBaseUrl());
        log.info("[Storage] Activating S3-compatible storage [bucket={}, endpoint={}, publicBaseUrl={}]",
                props.getBucket(),
                sanitizeEndpoint(props.getEndpoint()),
                props.getPublicBaseUrl());
        return new S3CompatibleStorageService(s3Client, props.getPublicBaseUrl());
    }

    private static void validateEndpoint(StorageProperties props) {
        if (props.getEndpoint() == null || props.getEndpoint().isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.endpoint must be provided when storage.enabled=true");
        }
    }

    private static void validateCredentials(StorageProperties props) {
        if (props.getAccessKey() == null || props.getAccessKey().isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.access-key must be provided when storage.enabled=true");
        }
        if (props.getSecretKey() == null || props.getSecretKey().isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.secret-key must be provided when storage.enabled=true");
        }
    }

    /**
     * Every report URL handed to the WhatsApp provider is built on this base, so a missing or malformed
     * value would fail each report after its PDF is already stored. Checked here instead, so the
     * deployment stops rather than the reports.
     */
    private static void validatePublicBaseUrl(String publicBaseUrl) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "[Storage] storage.public-base-url must be provided when storage.enabled=true");
        }
        URI base;
        try {
            base = new URI(publicBaseUrl.strip());
        } catch (URISyntaxException e) {
            throw invalidPublicBaseUrl();
        }
        boolean httpScheme = "http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme());
        if (!httpScheme || base.getHost() == null || base.getRawUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw invalidPublicBaseUrl();
        }
    }

    /** Does not echo the value, which may carry credentials in its user-info. */
    private static IllegalStateException invalidPublicBaseUrl() {
        return new IllegalStateException("[Storage] storage.public-base-url must be an absolute http(s) URL "
                + "with a host and no user-info, query or fragment");
    }

    /**
     * Warns when the report URLs could not be fetched from outside our network. Only a warning: this
     * bean cannot see whether WhatsApp report delivery is switched on, and a local or CI run against a
     * localhost store is valid. The WhatsApp sender refuses to start on the same check once a purpose
     * that hands out report URLs is live.
     *
     * <p>The two addresses are easy to conflate. {@code storage.endpoint} is where this service uploads
     * and is normally internal. {@code storage.public-base-url} is what the WhatsApp provider hands to
     * Meta, which downloads it from its own network, so it has to be public and anonymously readable.
     * An internal address uploads fine and then fails inside Meta with {@code (#131053) … blocked by a
     * destination filter}.
     */
    private static void warnIfPublicBaseUrlIsNotPublic(String publicBaseUrl) {
        String reason = PublicUrlValidator.unreachableReason(publicBaseUrl);
        if (reason != null) {
            log.warn("[Storage] storage.public-base-url '{}' is not publicly reachable ({}). The WhatsApp"
                            + " provider hands report URLs to Meta, which downloads them from the public"
                            + " internet, so documents built on this base will fail there with '(#131053) …"
                            + " blocked by a destination filter'. Set STORAGE_PUBLIC_BASE_URL to the public"
                            + " URL before enabling delivery.",
                    publicBaseUrl, reason);
        }
    }

    private static String sanitizeEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            int port = uri.getPort();
            String base = uri.getScheme() + "://" + (host != null ? host : "");
            return port > 0 ? base + ":" + port : base;
        } catch (Exception e) {
            return "<unparseable-endpoint>";
        }
    }
}
