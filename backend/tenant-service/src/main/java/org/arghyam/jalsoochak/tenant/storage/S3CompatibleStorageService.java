package org.arghyam.jalsoochak.tenant.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.arghyam.jalsoochak.tenant.config.properties.StorageProperties;
import org.arghyam.jalsoochak.tenant.exception.StorageException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

/**
 * S3-compatible implementation of {@link ObjectStorageService}.
 *
 * <p>Works with AWS S3 and any other store that implements the S3 API,
 * self-hosted or third-party.
 *
 * <p>Activated when {@code storage.access-key} is present in configuration.
 * Path-style access is enabled automatically when {@code storage.endpoint}
 * is set to a non-blank value, which most self-hosted / third-party
 * S3-compatible stores require.
 */
@Slf4j
@RequiredArgsConstructor
public class S3CompatibleStorageService implements ObjectStorageService {

    private final S3Client s3Client;
    private final StorageProperties props;

    @Override
    public String upload(String objectKey, InputStream content, long contentLength, String contentType) {
        try {
            log.debug("[Storage] Uploading object [key={}, contentType={}, size={}]", objectKey, contentType, contentLength);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(objectKey)
                            .contentType(contentType)
                            .contentLength(contentLength)
                            .build(),
                    RequestBody.fromInputStream(content, contentLength));
            log.debug("[Storage] Upload complete [key={}]", objectKey);
            return objectKey;
        } catch (SdkException e) {
            throw new StorageException("Upload failed for key: " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            log.debug("[Storage] Deleting object [key={}]", objectKey);
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(objectKey)
                            .build());
        } catch (NoSuchKeyException e) {
            log.debug("[Storage] Object not found during delete — already removed [key={}]", objectKey);
        } catch (SdkException e) {
            throw new StorageException("Delete failed for key: " + objectKey, e);
        }
    }

    /**
     * Downloads the object identified by {@code objectKey} from S3.
     *
     * <p><strong>Caller is responsible for closing the returned {@link InputStream}.</strong>
     * The stream wraps an active HTTP connection; failing to close it will leak the connection.
     * Always consume the stream inside a try-with-resources block, e.g.:
     * <pre>{@code
     * try (InputStream in = storageService.download(key)) {
     *     // read from in
     * }
     * }</pre>
     *
     * @param objectKey the storage key of the object to download
     * @return an {@link InputStream} backed by the S3 HTTP response — must be closed by the caller
     * @throws StorageException if the object does not exist or the download fails
     */
    @Override
    public InputStream download(String objectKey) {
        try {
            log.debug("[Storage] Downloading object [key={}]", objectKey);
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new StorageException("Object not found: " + objectKey, e);
        } catch (SdkException e) {
            throw new StorageException("Download failed for key: " + objectKey, e);
        }
    }
}
