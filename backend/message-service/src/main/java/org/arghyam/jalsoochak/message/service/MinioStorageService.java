package org.arghyam.jalsoochak.message.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.util.PublicUrlValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Uploads escalation and daily-report PDFs to MinIO and returns the URL that is handed to Glific.
 *
 * <p>Two distinct addresses are involved and conflating them is the failure mode to watch for:</p>
 * <ul>
 *   <li>{@code minio.endpoint} — where <em>this service</em> uploads from. Normally the internal
 *       address (cluster IP, container name, localhost).</li>
 *   <li>{@code minio.base-url} — the prefix of the URL handed to Glific, which Meta then downloads
 *       from <em>its own network</em>. This must be publicly reachable and anonymously readable, e.g.
 *       {@code https://jalsoochak.jjmbrain.in/minio}. An internal address here uploads fine and then
 *       fails inside Meta with {@code (#131053) … blocked by a destination filter}, delivering a
 *       document the officer cannot open.</li>
 * </ul>
 */
@Service
@Slf4j
public class MinioStorageService {

    /**
     * S3 error code returned to whichever caller loses a concurrent {@code makeBucket} race. Distinct
     * from {@code BucketAlreadyExists}, which means the name is taken by a <em>different</em> account
     * — that one is a real failure and is left to propagate.
     */
    private static final String BUCKET_ALREADY_OWNED_BY_YOU = "BucketAlreadyOwnedByYou";

    private final MinioClient minioClient;

    @Value("${minio.bucket:escalation-reports}")
    private String bucket;

    @Value("${minio.base-url:http://localhost:9000}")
    private String minioBaseUrl;

    @Autowired
    public MinioStorageService(
            @Value("${minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey) {
        if (accessKey == null || accessKey.isBlank()) {
            throw new IllegalStateException("minio.access-key must be configured (set MINIO_ACCESS_KEY env var)");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("minio.secret-key must be configured (set MINIO_SECRET_KEY env var)");
        }
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /** Package-private constructor for unit testing — accepts a pre-built MinioClient. */
    MinioStorageService(MinioClient minioClient, String bucket, String minioBaseUrl) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.minioBaseUrl = minioBaseUrl;
    }

    /**
     * Warns at startup when {@code minio.base-url} could not be fetched by Glific. Only a warning
     * here — this bean has no view of whether WhatsApp document delivery is switched on, and a local
     * or CI run with a localhost MinIO is perfectly valid. {@code GlificWhatsAppService} turns the
     * same check into a startup failure when a delivery purpose is actually live.
     */
    @PostConstruct
    void warnIfBaseUrlIsNotPublic() {
        String reason = PublicUrlValidator.unreachableReason(minioBaseUrl);
        if (reason != null) {
            log.warn("[MinIO] minio.base-url '{}' is not publicly reachable ({}). Glific hands report"
                            + " URLs to Meta, which downloads them from the public internet — documents"
                            + " built with this prefix will fail there with '(#131053) … blocked by a"
                            + " destination filter'. Set MINIO_BASE_URL to the public URL"
                            + " (e.g. https://jalsoochak.jjmbrain.in/minio) before enabling delivery.",
                    minioBaseUrl, reason);
        }
    }

    /**
     * Uploads the local PDF file at {@code localPath} to MinIO and returns the URL to hand to Glific.
     *
     * @param localPath local path to the PDF file
     * @return public URL pointing to the uploaded object
     */
    public String upload(Path localPath) throws Exception {
        String objectName = localPath.getFileName().toString();
        log.info("[MinIO] Uploading escalation report: {}", objectName);
        minioClient.uploadObject(UploadObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .filename(localPath.toString())
                .contentType("application/pdf")
                .build());
        String url = publicUrlFor(objectName);
        log.info("[MinIO] Upload complete: {}", url);
        return url;
    }

    /**
     * Uploads a report PDF to a named bucket under a folder-structured object key, and returns the
     * public URL to hand to Glific.
     *
     * <p>The water reports live in their own buckets ({@code daily-water-reports},
     * {@code weekly-water-reports}) with a folder per role and per period. The bucket and the key
     * both travel inside the WhatsApp template's URL <em>variable</em>, never its frozen prefix, so
     * this layout needs no new Meta template approval.</p>
     *
     * <p>The bucket is created if absent, but <strong>anonymous read is not granted here</strong> —
     * that is a deliberate out-of-band step ({@code mc anonymous set download <alias>/<bucket>}),
     * because the officer's phone fetches this URL with no credentials and a bucket silently made
     * public by application code is a bad default for a store holding PII-bearing reports.</p>
     *
     * @param objectKey path within the bucket, e.g. {@code SO/2026-07-19/daily_water_report_….pdf}
     */
    public String upload(Path localPath, String targetBucket, String objectKey) throws Exception {
        ensureBucketExists(targetBucket);
        log.info("[MinIO] Uploading report to {}/{}", targetBucket, objectKey);
        minioClient.uploadObject(UploadObjectArgs.builder()
                .bucket(targetBucket)
                .object(objectKey)
                .filename(localPath.toString())
                .contentType("application/pdf")
                .build());
        String url = publicUrlFor(targetBucket, objectKey);
        log.info("[MinIO] Upload complete: {}", url);
        return url;
    }

    /**
     * Creates the target bucket if it is absent.
     *
     * <p>The exists-then-create pair is not atomic, and the report jobs upload one PDF per officer
     * concurrently: on the first run after a bucket is added, several uploads see it missing and all
     * of them call {@code makeBucket}. One wins and the losers get {@code BucketAlreadyOwnedByYou},
     * which is this method's desired end state, not a failure — treating it as one failed those
     * officers' reports for no reason. Any other error still propagates.</p>
     */
    private void ensureBucketExists(String targetBucket) throws Exception {
        if (minioClient.bucketExists(BucketExistsArgs.builder().bucket(targetBucket).build())) {
            return;
        }
        log.info("[MinIO] Bucket '{}' does not exist — creating it. Grant anonymous read separately"
                + " (mc anonymous set download <alias>/{}), or the report link will 403 on the"
                + " officer's phone.", targetBucket, targetBucket);
        try {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(targetBucket).build());
        } catch (ErrorResponseException e) {
            if (!isAlreadyOwned(e)) {
                throw e;
            }
            log.debug("[MinIO] Bucket '{}' was created concurrently — continuing.", targetBucket);
        }
    }

    /** True when {@code makeBucket} lost a race with another upload that created the same bucket. */
    private static boolean isAlreadyOwned(ErrorResponseException e) {
        return e.errorResponse() != null
                && BUCKET_ALREADY_OWNED_BY_YOU.equals(e.errorResponse().code());
    }

    /**
     * Builds the Glific-facing URL for an uploaded object.
     *
     * <p>Trailing slashes on the configured prefix are trimmed, because {@code MINIO_BASE_URL} is
     * hand-written per environment and a value ending in {@code /minio/} would otherwise produce
     * {@code //escalation-reports/…}, which some reverse proxies answer with a 404 rather than
     * normalising. The object name is percent-encoded so a filename is never able to produce a
     * malformed URL; today's report names contain no characters that require it.</p>
     */
    String publicUrlFor(String objectName) {
        return publicUrlFor(bucket, objectName);
    }

    /**
     * As {@link #publicUrlFor(String)} for an explicit bucket and a possibly folder-structured key.
     *
     * <p>Each path segment is encoded separately so the {@code /} separators survive — encoding the
     * whole key would turn {@code SO/2026-07-19/file.pdf} into one literal object name containing
     * {@code %2F}, which MinIO would serve from a different (non-existent) path.</p>
     */
    String publicUrlFor(String targetBucket, String objectKey) {
        String prefix = minioBaseUrl == null ? "" : minioBaseUrl.trim();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/" + targetBucket + "/" + encodeObjectKey(objectKey);
    }

    /** Percent-encodes each segment of an object key, preserving the {@code /} separators. */
    private static String encodeObjectKey(String objectKey) {
        StringBuilder out = new StringBuilder(objectKey.length());
        String[] segments = objectKey.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(encodePathSegment(segments[i]));
        }
        return out.toString();
    }

    /** Percent-encodes a single path segment ({@code URLEncoder} is form encoding — spaces need fixing up). */
    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
