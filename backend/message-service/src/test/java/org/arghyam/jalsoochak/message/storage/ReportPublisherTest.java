package org.arghyam.jalsoochak.message.storage;

import org.arghyam.jalsoochak.message.channel.provider.ReportDeliveryMode;
import org.arghyam.jalsoochak.message.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportPublisher")
class ReportPublisherTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);
    private static final String BUCKET = "daily-water-reports";
    private static final String KEY = "SO/2026-07-19/daily_water_report_SECTION_OFFICER_21343_2026-07-19.pdf";
    private static final String RECORD_URL = "https://sdc.example.org/minio/" + BUCKET + "/" + KEY;
    private static final String DELIVERY_BUCKET = "jalsoochak-report-delivery";
    /** {@code <record bucket>/<random UUID>/<filename>}. */
    private static final String DELIVERY_KEY_PATTERN =
            BUCKET + "/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                    + "/daily_water_report_SECTION_OFFICER_21343_2026-07-19\\.pdf";

    @Mock ObjectStorageService recordStore;
    @Mock ObjectStorageService deliveryStore;
    @Mock AutoCloseable deliveryClient;

    @TempDir Path tempDir;

    private Path pdf;

    @BeforeEach
    void setUp() throws Exception {
        pdf = Files.write(tempDir.resolve("report.pdf"), PDF_BYTES);
    }

    @Nested
    @DisplayName("without a delivery store")
    class RecordOnly {

        private ReportPublisher publisher() {
            return new ReportPublisher(recordStore);
        }

        @ParameterizedTest(name = "mode={0}")
        @NullSource
        @EnumSource(ReportDeliveryMode.class)
        @DisplayName("every mode is stored in the record bucket, created if missing, and gets the record URL")
        void everyModeGetsTheRecordUrl(ReportDeliveryMode mode) throws Exception {
            when(recordStore.publicUrl(BUCKET, KEY)).thenReturn(URI.create(RECORD_URL));

            String url = publisher().publish(pdf, BUCKET, KEY, mode);

            assertThat(url).isEqualTo(RECORD_URL);
            verify(recordStore).ensureBucket(BUCKET);
            verify(recordStore).upload(eq(BUCKET), eq(KEY), any(InputStream.class),
                    eq((long) PDF_BYTES.length), eq("application/pdf"));
        }

        @Test
        @DisplayName("closing it closes nothing")
        void closeIsANoOp() throws Exception {
            publisher().close();

            verifyNoInteractions(recordStore);
        }
    }

    @Nested
    @DisplayName("with a delivery store")
    class WithDeliveryStore {

        private ReportPublisher publisher;

        @BeforeEach
        void setUp() {
            publisher = new ReportPublisher(recordStore, deliveryStore, DELIVERY_BUCKET, deliveryClient);
        }

        @Test
        @DisplayName("a document is stored in both, and the delivery copy's URL is returned")
        void documentIsStagedInTheDeliveryStore() throws Exception {
            when(deliveryStore.publicUrl(eq(DELIVERY_BUCKET), anyString()))
                    .thenAnswer(inv -> URI.create("https://reports.example.org/" + DELIVERY_BUCKET + "/"
                            + inv.getArgument(1, String.class)));

            String url = publisher.publish(pdf, BUCKET, KEY, ReportDeliveryMode.DOCUMENT);

            verify(recordStore).ensureBucket(BUCKET);
            verify(recordStore).upload(eq(BUCKET), eq(KEY), any(InputStream.class),
                    eq((long) PDF_BYTES.length), eq("application/pdf"));
            ArgumentCaptor<String> deliveryKey = ArgumentCaptor.forClass(String.class);
            verify(deliveryStore).upload(eq(DELIVERY_BUCKET), deliveryKey.capture(), any(InputStream.class),
                    eq((long) PDF_BYTES.length), eq("application/pdf"));
            assertThat(deliveryKey.getValue()).matches(DELIVERY_KEY_PATTERN);
            assertThat(url).isEqualTo("https://reports.example.org/" + DELIVERY_BUCKET + "/" + deliveryKey.getValue());
            verify(recordStore, never()).publicUrl(anyString(), anyString());
        }

        /** The bucket is pre-created with its read and expiry rules; this service must never make one. */
        @Test
        @DisplayName("the delivery bucket is never created")
        void deliveryBucketIsNeverCreated() throws Exception {
            when(deliveryStore.publicUrl(eq(DELIVERY_BUCKET), anyString())).thenReturn(URI.create("https://r/x.pdf"));

            publisher.publish(pdf, BUCKET, KEY, ReportDeliveryMode.DOCUMENT);

            verify(deliveryStore, never()).ensureBucket(anyString());
        }

        @Test
        @DisplayName("each document copy gets its own random path, so a retry never reuses a URL")
        void eachCopyGetsItsOwnRandomPath() throws Exception {
            when(deliveryStore.publicUrl(eq(DELIVERY_BUCKET), anyString())).thenReturn(URI.create("https://r/x.pdf"));

            publisher.publish(pdf, BUCKET, KEY, ReportDeliveryMode.DOCUMENT);
            publisher.publish(pdf, BUCKET, KEY, ReportDeliveryMode.DOCUMENT);

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(deliveryStore, times(2)).upload(eq(DELIVERY_BUCKET), keys.capture(), any(InputStream.class),
                    anyLong(), anyString());
            assertThat(keys.getAllValues()).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("both stores receive the whole PDF")
        void bothStoresReceiveTheWholePdf() throws Exception {
            List<byte[]> received = new ArrayList<>();
            doAnswer(inv -> received.add(inv.getArgument(2, InputStream.class).readAllBytes()))
                    .when(recordStore).upload(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
            doAnswer(inv -> received.add(inv.getArgument(2, InputStream.class).readAllBytes()))
                    .when(deliveryStore).upload(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
            when(deliveryStore.publicUrl(eq(DELIVERY_BUCKET), anyString())).thenReturn(URI.create("https://r/x.pdf"));

            publisher.publish(pdf, BUCKET, KEY, ReportDeliveryMode.DOCUMENT);

            assertThat(received).hasSize(2).allSatisfy(bytes -> assertThat(bytes).isEqualTo(PDF_BYTES));
        }

        /**
         * A link template's button prefix is frozen to the store-of-record URL, and a null mode is a
         * report no send will fetch, such as one suppressed by a dry-run flag.
         */
        @ParameterizedTest(name = "mode={0}")
        @NullSource
        @EnumSource(value = ReportDeliveryMode.class, names = "LINK")
        @DisplayName("a link, or no send at all, is stored in the record store only and gets the record URL")
        void linkGetsTheRecordUrl(ReportDeliveryMode mode) throws Exception {
            when(recordStore.publicUrl(BUCKET, KEY)).thenReturn(URI.create(RECORD_URL));

            String url = publisher.publish(pdf, BUCKET, KEY, mode);

            assertThat(url).isEqualTo(RECORD_URL);
            verify(recordStore).upload(eq(BUCKET), eq(KEY), any(InputStream.class), anyLong(), anyString());
            verifyNoInteractions(deliveryStore);
        }

        @Test
        @DisplayName("a failed record upload throws before anything reaches the delivery store")
        void failedRecordUploadStopsThePublish() {
            doThrow(new StorageException("Upload failed for key: " + KEY))
                    .when(recordStore).upload(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());

            assertThatThrownBy(() -> publisher.publish(pdf, BUCKET, KEY, ReportDeliveryMode.DOCUMENT))
                    .isInstanceOf(StorageException.class);
            verifyNoInteractions(deliveryStore);
        }

        @Test
        @DisplayName("a failed delivery upload throws, so the document is not sent")
        void failedDeliveryUploadStopsThePublish() {
            doThrow(new StorageException("Upload failed"))
                    .when(deliveryStore).upload(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());

            assertThatThrownBy(() -> publisher.publish(pdf, BUCKET, KEY, ReportDeliveryMode.DOCUMENT))
                    .isInstanceOf(StorageException.class);
            verify(deliveryStore, never()).publicUrl(anyString(), anyString());
        }

        @Test
        @DisplayName("closing it closes the delivery store's client, which nothing else owns")
        void closeClosesTheDeliveryClient() throws Exception {
            publisher.close();

            verify(deliveryClient).close();
        }
    }
}
