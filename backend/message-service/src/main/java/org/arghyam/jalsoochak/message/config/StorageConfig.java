package org.arghyam.jalsoochak.message.config;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.storage.ObjectStorageService;
import org.arghyam.jalsoochak.message.storage.ReportPublisher;
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
 * Wires the S3-compatible {@link ObjectStorageService} and the {@link ReportPublisher} over it when
 * {@code storage.enabled=true}.
 *
 * <p>There is deliberately no no-op fallback for a disabled store: this service has no degraded
 * mode for storage, so anything that needs {@link ObjectStorageService} fails at startup rather
 * than every upload silently going nowhere.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@Slf4j
public class StorageConfig {

    private static final String STORAGE_SWITCH = "storage.enabled";
    private static final String DELIVERY_SWITCH = "storage.delivery.enabled";

    /**
     * Talks to {@code storage.endpoint} with path-style access, which most S3-compatible stores other
     * than AWS require. The endpoint is required: there is no implicit AWS default, so a deployment
     * that misses it stops at startup instead of uploading to AWS.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public S3Client s3Client(StorageProperties props) {
        requireConfigured(props.getEndpoint(), "storage.endpoint", STORAGE_SWITCH);
        requireConfigured(props.getAccessKey(), "storage.access-key", STORAGE_SWITCH);
        requireConfigured(props.getSecretKey(), "storage.secret-key", STORAGE_SWITCH);
        log.info("[Storage] Using endpoint: {} (path-style enabled)", sanitizeEndpoint(props.getEndpoint()));
        return buildClient(props.getEndpoint(), props.getRegion(), props.getAccessKey(), props.getSecretKey());
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public ObjectStorageService objectStorageService(S3Client s3Client, StorageProperties props) {
        validatePublicBaseUrl(props.getPublicBaseUrl(), "storage.public-base-url", STORAGE_SWITCH);
        warnIfPublicBaseUrlIsNotPublic(props.getPublicBaseUrl(), "storage.public-base-url", "STORAGE_PUBLIC_BASE_URL");
        log.info("[Storage] Activating S3-compatible storage [bucket={}, endpoint={}, publicBaseUrl={}]",
                props.getBucket(),
                sanitizeEndpoint(props.getEndpoint()),
                props.getPublicBaseUrl());
        return new S3CompatibleStorageService(s3Client, props.getPublicBaseUrl());
    }

    /**
     * Publishes to {@code objectStorageService}, and with {@code storage.delivery.enabled=true} also
     * stages document sends in the external delivery store.
     *
     * <p>The delivery store's client and adapter are built here and handed to the publisher alone,
     * which closes the client when the context shuts down. They are not beans: a second
     * {@link S3Client} or {@link ObjectStorageService} bean would make every existing injection of
     * those by type ambiguous.
     */
    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    public ReportPublisher reportPublisher(ObjectStorageService objectStorageService, StorageProperties props) {
        StorageProperties.Delivery delivery = props.getDelivery();
        if (!delivery.isEnabled()) {
            return new ReportPublisher(objectStorageService);
        }
        requireConfigured(delivery.getEndpoint(), "storage.delivery.endpoint", DELIVERY_SWITCH);
        requireConfigured(delivery.getRegion(), "storage.delivery.region", DELIVERY_SWITCH);
        requireConfigured(delivery.getAccessKey(), "storage.delivery.access-key", DELIVERY_SWITCH);
        requireConfigured(delivery.getSecretKey(), "storage.delivery.secret-key", DELIVERY_SWITCH);
        requireConfigured(delivery.getBucket(), "storage.delivery.bucket", DELIVERY_SWITCH);
        validatePublicBaseUrl(delivery.getPublicBaseUrl(), "storage.delivery.public-base-url", DELIVERY_SWITCH);
        warnIfPublicBaseUrlIsNotPublic(delivery.getPublicBaseUrl(), "storage.delivery.public-base-url",
                "STORAGE_DELIVERY_PUBLIC_BASE_URL");
        log.info("[Storage] Activating external delivery store for document sends [bucket={}, endpoint={},"
                        + " publicBaseUrl={}]",
                delivery.getBucket(), sanitizeEndpoint(delivery.getEndpoint()), delivery.getPublicBaseUrl());
        S3Client deliveryClient = buildClient(delivery.getEndpoint(), delivery.getRegion(),
                delivery.getAccessKey(), delivery.getSecretKey());
        return new ReportPublisher(objectStorageService,
                new S3CompatibleStorageService(deliveryClient, delivery.getPublicBaseUrl()),
                delivery.getBucket(), deliveryClient);
    }

    /** Path-style access, which most S3-compatible stores other than AWS require. */
    private static S3Client buildClient(String endpoint, String region, String accessKey, String secretKey) {
        return S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private static void requireConfigured(String value, String property, String switchProperty) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "[Storage] " + property + " must be provided when " + switchProperty + "=true");
        }
    }

    /**
     * Every report URL handed to the WhatsApp provider is built on this base, so a missing or malformed
     * value would fail each report after its PDF is already stored. Checked here instead, so the
     * deployment stops rather than the reports.
     */
    private static void validatePublicBaseUrl(String publicBaseUrl, String property, String switchProperty) {
        requireConfigured(publicBaseUrl, property, switchProperty);
        URI base;
        try {
            base = new URI(publicBaseUrl.strip());
        } catch (URISyntaxException e) {
            throw invalidPublicBaseUrl(property);
        }
        boolean httpScheme = "http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme());
        if (!httpScheme || base.getHost() == null || base.getRawUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw invalidPublicBaseUrl(property);
        }
    }

    /** Does not echo the value, which may carry credentials in its user-info. */
    private static IllegalStateException invalidPublicBaseUrl(String property) {
        return new IllegalStateException("[Storage] " + property + " must be an absolute http(s) URL "
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
    private static void warnIfPublicBaseUrlIsNotPublic(String publicBaseUrl, String property, String envVar) {
        String reason = PublicUrlValidator.unreachableReason(publicBaseUrl);
        if (reason != null) {
            log.warn("[Storage] {} '{}' is not publicly reachable ({}). The WhatsApp"
                            + " provider hands report URLs to Meta, which downloads them from the public"
                            + " internet, so documents built on this base will fail there with '(#131053) …"
                            + " blocked by a destination filter'. Set {} to the public"
                            + " URL before enabling delivery.",
                    property, publicBaseUrl, reason, envVar);
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
