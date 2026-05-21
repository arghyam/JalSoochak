package org.arghyam.jalsoochak.scheme.service;

import io.minio.MinioClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

@Service
public class MinioService {

    private final MinioClient minioClient;
    private final String bucket;
    private final String endpoint;
    private final String presignedBaseUrl;
    private final Duration presignedTtl;

    public MinioService(@Value("${minio.endpoint}") String endpoint,
                        @Value("${minio.access-key}") String accessKey,
                        @Value("${minio.secret-key}") String secretKey,
                        @Value("${minio.bucket}") String bucket,
                        @Value("${minio.presigned-base-url:}") String presignedBaseUrl,
                        @Value("${minio.presigned-ttl-seconds:3600}") long presignedTtlSeconds) {
        this.bucket = bucket;
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.presignedBaseUrl = presignedBaseUrl;
        this.presignedTtl = Duration.ofSeconds(Math.max(presignedTtlSeconds, 1L));
        this.minioClient = MinioClient.builder()
                .endpoint(this.endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    public String upload(byte[] file, String objectName) {
        try (InputStream inputStream = new ByteArrayInputStream(file)) {
            return upload(inputStream, file.length, objectName, "text/csv");
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload report to MinIO", e);
        }
    }

    public String upload(InputStream inputStream, long size, String objectName, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(inputStream, size, -1)
                            .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                            .build()
            );
            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload report to MinIO", e);
        }
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectUrl(String objectName) {
        try {
            int expirySeconds = Math.toIntExact(presignedTtl.getSeconds());
            String sdkUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectName)
                            .expiry(expirySeconds)
                            .build()
            );
            return rewritePublicUrl(sdkUrl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL for MinIO object", e);
        }
    }

    private String rewritePublicUrl(String sdkUrl) {
        if (presignedBaseUrl == null || presignedBaseUrl.isBlank()) {
            return sdkUrl;
        }
        try {
            URI sdkUri = new URI(sdkUrl);
            URI base = URI.create(presignedBaseUrl.strip());
            String rawBasePath = base.getPath() == null ? "" : base.getPath();
            if (rawBasePath.endsWith("/")) {
                rawBasePath = rawBasePath.substring(0, rawBasePath.length() - 1);
            }

            StringBuilder out = new StringBuilder();
            out.append(base.getScheme()).append("://").append(base.getHost());
            if (base.getPort() != -1) {
                out.append(":").append(base.getPort());
            }
            out.append(rawBasePath).append(sdkUri.getRawPath());
            if (sdkUri.getRawQuery() != null) {
                out.append("?").append(sdkUri.getRawQuery());
            }
            return out.toString();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to rewrite MinIO presigned URL", e);
        }
    }
}
