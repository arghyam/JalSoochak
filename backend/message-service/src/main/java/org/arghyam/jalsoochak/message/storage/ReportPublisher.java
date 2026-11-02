package org.arghyam.jalsoochak.message.storage;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.provider.ReportDeliveryMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores a rendered report PDF and returns the URL handed to the WhatsApp provider.
 *
 * <p>Every report goes to the store of record. When an external delivery store is configured, a
 * report sent as a WhatsApp document is also copied there, because Meta downloads a document
 * itself and some deployments' store of record is out of its reach. See
 * {@code StorageProperties.Delivery}.
 */
@Slf4j
public class ReportPublisher implements AutoCloseable {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final ObjectStorageService recordStore;
    private final ObjectStorageService deliveryStore;
    private final String deliveryBucket;
    private final AutoCloseable deliveryClient;

    /** Publishes to the store of record only. */
    public ReportPublisher(ObjectStorageService recordStore) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.deliveryStore = null;
        this.deliveryBucket = null;
        this.deliveryClient = null;
    }

    /**
     * Also stages document sends in {@code deliveryStore}, under {@code deliveryBucket}.
     *
     * @param deliveryClient the client behind {@code deliveryStore}. This publisher owns it and closes
     *                       it on {@link #close()}, since nothing else holds it
     */
    public ReportPublisher(ObjectStorageService recordStore, ObjectStorageService deliveryStore,
                           String deliveryBucket, AutoCloseable deliveryClient) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore");
        this.deliveryBucket = Objects.requireNonNull(deliveryBucket, "deliveryBucket");
        this.deliveryClient = Objects.requireNonNull(deliveryClient, "deliveryClient");
    }

    /**
     * Uploads {@code pdf} to the store of record at {@code bucket}/{@code objectKey}, creating the
     * bucket first if it is missing, and returns the URL to send.
     *
     * <p>For a {@link ReportDeliveryMode#DOCUMENT} send with a delivery store configured, the PDF is
     * copied there too and the copy's URL is returned. Otherwise the store-of-record URL is returned:
     * a link template's button prefix is frozen to it at approval, and a {@code null} mode means no
     * send will fetch the report, so no copy leaves the store of record.
     *
     * <p>Both uploads finish before this returns, so a failure in either store stops the send.
     *
     * @throws IOException when the PDF cannot be read
     * @throws org.arghyam.jalsoochak.message.exception.StorageException when either store refuses
     *         the bucket check or the upload
     */
    public String publish(Path pdf, String bucket, String objectKey, ReportDeliveryMode mode) throws IOException {
        recordStore.ensureBucket(bucket);
        upload(recordStore, pdf, bucket, objectKey);
        if (mode != ReportDeliveryMode.DOCUMENT || deliveryStore == null) {
            return recordStore.publicUrl(bucket, objectKey).toString();
        }
        String deliveryKey = deliveryKey(bucket, objectKey);
        upload(deliveryStore, pdf, deliveryBucket, deliveryKey);
        log.debug("[Storage] Staged {}/{} for document delivery as {}/{}", bucket, objectKey, deliveryBucket, deliveryKey);
        return deliveryStore.publicUrl(deliveryBucket, deliveryKey).toString();
    }

    @Override
    public void close() throws Exception {
        if (deliveryClient != null) {
            deliveryClient.close();
        }
    }

    /**
     * {@code <bucket>/<random UUID>/<filename>}, e.g.
     * {@code daily-water-reports/0b9d…/daily_water_report_SECTION_OFFICER_21343_2026-07-19.pdf}.
     *
     * <p>The store-of-record key is built from an officer id and a date, so anyone can guess it. The
     * copy can be read without credentials and carries phone numbers, so the random segment is what
     * keeps it from being found. The bucket prefix shows which record copy it came from.
     */
    private static String deliveryKey(String bucket, String objectKey) {
        String filename = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        return bucket + "/" + UUID.randomUUID() + "/" + filename;
    }

    private static void upload(ObjectStorageService store, Path pdf, String bucket, String objectKey)
            throws IOException {
        try (InputStream content = Files.newInputStream(pdf)) {
            store.upload(bucket, objectKey, content, Files.size(pdf), PDF_CONTENT_TYPE);
        }
    }
}
