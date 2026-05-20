package org.arghyam.jalsoochak.scheme.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

@Service
public class MinioService {

    private final MinioClient minioClient;
    private final String bucket;
    private final String endpoint;

    public MinioService(@Value("${minio.endpoint}") String endpoint,
                        @Value("${minio.access-key}") String accessKey,
                        @Value("${minio.secret-key}") String secretKey,
                        @Value("${minio.bucket}") String bucket) {
        this.bucket = bucket;
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
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
            return buildObjectUrl(objectName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload report to MinIO", e);
        }
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectUrl(String objectName) {
        return buildObjectUrl(objectName);
    }

    private String buildObjectUrl(String objectName) {
        try {
            URI endpointUri = new URI(endpoint);
            String path = String.format("/%s/%s", bucket, objectName);
            return new URI(endpointUri.getScheme(), endpointUri.getAuthority(), path, null, null).toString();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to construct MinIO object URL", e);
        }
    }
}
