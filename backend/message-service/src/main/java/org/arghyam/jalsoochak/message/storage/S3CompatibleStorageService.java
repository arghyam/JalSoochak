package org.arghyam.jalsoochak.message.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.exception.StorageException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * {@link ObjectStorageService} over any store that speaks the AWS S3 API.
 */
@Slf4j
@RequiredArgsConstructor
public class S3CompatibleStorageService implements ObjectStorageService {

    private final S3Client s3Client;
    /** {@code storage.public-base-url} — the address report links handed to the WhatsApp provider are built on. */
    private final String publicBaseUrl;

    /**
     * {@inheritDoc}
     *
     * <p>The check-then-create pair is not atomic, and the report jobs upload one PDF per officer
     * concurrently: on the first run after a bucket is added, several uploads see it missing and all
     * of them try to create it. The losers get {@code BucketAlreadyOwnedByYou}, which is the state
     * wanted, not a failure. {@code BucketAlreadyExists} is different: the name belongs to another
     * account, so it propagates.
     */
    @Override
    public void ensureBucket(String bucket) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return;
        } catch (NoSuchBucketException e) {
            log.info("[Storage] Bucket '{}' does not exist — creating it. Grant it anonymous read"
                    + " separately, or the report link will 403 on the officer's phone.", bucket);
        } catch (SdkException e) {
            throw new StorageException("Could not check bucket: " + bucket, e);
        }
        try {
            s3Client.createBucket(createBucketRequest(bucket));
        } catch (BucketAlreadyOwnedByYouException e) {
            log.debug("[Storage] Bucket '{}' was created concurrently — continuing.", bucket);
        } catch (SdkException e) {
            throw new StorageException("Could not create bucket: " + bucket, e);
        }
    }

    /**
     * Outside {@code us-east-1}, AWS refuses a create request that does not name the client's own
     * region as the bucket's location.
     */
    private CreateBucketRequest createBucketRequest(String bucket) {
        CreateBucketRequest.Builder request = CreateBucketRequest.builder().bucket(bucket);
        Region region = s3Client.serviceClientConfiguration().region();
        if (region != null && !Region.US_EAST_1.equals(region)) {
            request.createBucketConfiguration(config -> config.locationConstraint(region.id()));
        }
        return request.build();
    }

    @Override
    public void upload(String bucket, String objectKey, InputStream content, long contentLength, String contentType) {
        try {
            log.debug("[Storage] Uploading [bucket={}, key={}, contentType={}, size={}]",
                    bucket, objectKey, contentType, contentLength);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .contentLength(contentLength)
                            .build(),
                    RequestBody.fromInputStream(content, contentLength));
        } catch (SdkException e) {
            throw new StorageException("Upload failed for key: " + objectKey, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Trailing slashes on the configured base URL are trimmed, because the value is hand-written
     * per environment and one ending in {@code /} would otherwise produce {@code //bucket/…}, which
     * some reverse proxies answer with a 404 rather than normalising.
     */
    @Override
    public URI publicUrl(String bucket, String objectKey) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new StorageException("storage.public-base-url is not configured");
        }
        String prefix = publicBaseUrl.trim();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        try {
            return URI.create(prefix + "/" + bucket + "/" + encodeObjectKey(objectKey));
        } catch (IllegalArgumentException e) {
            throw new StorageException("Failed to build public URL for key: " + objectKey, e);
        }
    }

    /**
     * Percent-encodes each segment of an object key, preserving the {@code /} separators. Encoding
     * the whole key would turn {@code SO/2026-07-19/file.pdf} into one literal object name
     * containing {@code %2F}, which the store would serve from a different (non-existent) path.
     */
    private static String encodeObjectKey(String objectKey) {
        StringBuilder out = new StringBuilder(objectKey.length());
        String[] segments = objectKey.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            // URLEncoder is form encoding, so a space comes out as '+' and needs fixing up.
            out.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }
}
