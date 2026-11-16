package org.arghyam.jalsoochak.message.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Unit tests for {@link KafkaConfig}'s consumer settings and {@link KafkaConfig#neverBlockingRecoverer}.
 *
 * <p>A recoverer that throws makes {@code DefaultErrorHandler} seek back to the failed offset, which
 * re-runs the listener forever. For handlers that send WhatsApp messages that loop is not a retry, it
 * is a broadcast — the incident behind this test had an officer receiving the same daily report over
 * and over while the dead-letter publish kept failing.</p>
 */
@ExtendWith(MockitoExtension.class)
class KafkaConfigTest {

    @Mock
    private ConsumerRecordRecoverer delegate;

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("common-topic", 0, 2831582L, "key", "{\"eventType\":\"DAILY_REPORT_KPIS\"}");
    }

    @Test
    void neverBlockingRecoverer_passesTheRecordAndExceptionThroughUnchanged() {
        ConsumerRecordRecoverer wrapped = KafkaConfig.neverBlockingRecoverer(delegate);
        ConsumerRecord<String, String> rec = record();
        IllegalStateException cause = new IllegalStateException("delivery failed");

        wrapped.accept(rec, cause);

        // Neither ConsumerRecord nor Exception overrides equals, so matching on the instances is an
        // identity check: the wrapper must hand the delegate exactly what it was given, not a rebuilt
        // record or a re-wrapped cause, or the dead-letter headers derived from them would change.
        verify(delegate).accept(rec, cause);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void neverBlockingRecoverer_swallowsADeadLetterPublishFailure() {
        doThrow(new IllegalStateException("Topic common-topic.DLT not present in metadata"))
                .when(delegate).accept(any(), any());
        ConsumerRecordRecoverer wrapped = KafkaConfig.neverBlockingRecoverer(delegate);

        // Must not propagate: a thrown recoverer is what turns the retry budget into an endless loop.
        assertThatCode(() -> wrapped.accept(record(), new IllegalStateException("delivery failed")))
                .doesNotThrowAnyException();
    }

    @Test
    void neverBlockingRecoverer_toleratesANullOriginalException() {
        doThrow(new IllegalStateException("DLT unavailable"))
                .when(delegate).accept(any(), any());
        ConsumerRecordRecoverer wrapped = KafkaConfig.neverBlockingRecoverer(delegate);

        assertThatCode(() -> wrapped.accept(record(), null)).doesNotThrowAnyException();
    }

    // ───────────────────────── payload redaction ─────────────────────────

    /**
     * The dropped-record payload is only ever logged at DEBUG, and phone numbers must be masked even
     * there: notification events carry operator and officer mobile numbers, which are PII.
     */
    @Test
    void redactPhoneNumbers_masksAllButTheLastFourDigits() {
        String redacted = KafkaConfig.redactPhoneNumbers(
                "{\"eventType\":\"NUDGE\",\"phone\":\"919876500025\",\"mobile\":\"9876500026\"}");

        assertThat(redacted)
                .doesNotContain("919876500025")
                .doesNotContain("9876500026")
                .contains("********0025")
                .contains("******0026");
    }

    /** Short numbers — a tenant id, a partition — carry no PII and must stay readable. */
    @Test
    void redactPhoneNumbers_leavesShortNumbersAndNullAlone() {
        assertThat(KafkaConfig.redactPhoneNumbers("{\"tenantId\":1,\"officerUserId\":500}"))
                .isEqualTo("{\"tenantId\":1,\"officerUserId\":500}");
        assertThat(KafkaConfig.redactPhoneNumbers(null)).isNull();
    }

    // ───────────────────────── consumer settings ─────────────────────────

    /**
     * The service's own consumer factory replaces Spring Boot's, so it must apply the
     * {@code spring.kafka.consumer.*} settings itself. Without them the Kafka defaults run (500
     * records per poll, a 300s poll interval), and a poll of pump-operator batches can outlast the
     * interval: the consumer is evicted and the whole poll is delivered, and sent, again.
     */
    @Test
    void consumerFactory_appliesTheConfiguredConsumerSettings() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("broker:9092"));
        properties.getConsumer().setGroupId("message-service-group");
        properties.getConsumer().setMaxPollRecords(5);
        properties.getConsumer().getProperties().put("max.poll.interval.ms", "600000");

        assertThat(consumerConfig(properties))
                .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, List.of("broker:9092"))
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "message-service-group")
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5)
                .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "600000")
                .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    }

    @Test
    void consumerFactory_readsFromTheEarliestOffset_unlessConfiguredOtherwise() {
        assertThat(consumerConfig(new KafkaProperties()))
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        KafkaProperties latest = new KafkaProperties();
        latest.getConsumer().setAutoOffsetReset("latest");
        assertThat(consumerConfig(latest))
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    }

    private static Map<String, Object> consumerConfig(KafkaProperties properties) {
        KafkaConfig config = new KafkaConfig(properties,
                new StaticListableBeanFactory().getBeanProvider(SslBundles.class));
        return config.consumerFactory().getConfigurationProperties();
    }
}
