package org.arghyam.jalsoochak.message.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.util.backoff.FixedBackOff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Unit tests for {@link KafkaConfig#neverBlockingRecoverer}.
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

    /** Minimal {@link ObjectProvider} so the config can be built without a Spring context. */
    private record SimpleObjectProvider<T>(T value) implements ObjectProvider<T> {
        @Override public T getObject() { return value; }
        @Override public T getObject(Object... args) { return value; }
        @Override public T getIfAvailable() { return value; }
        @Override public T getIfUnique() { return value; }
    }

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

    // ───────────────────────── dead-letter metrics ─────────────────────────

    /**
     * Nothing consumes the dead-letter topics, so from outside the service a lost notification and a
     * delivered one look identical: silence. These counters are the only thing that makes the
     * difference alertable.
     */
    @Test
    void neverBlockingRecoverer_countsARecordItSuccessfullyDeadLetters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerRecordRecoverer wrapped = KafkaConfig.neverBlockingRecoverer(delegate, registry);

        wrapped.accept(record(), new IllegalStateException("delivery failed"));

        assertThat(counterValue(registry, "deadlettered")).isEqualTo(1.0);
        assertThat(counterValue(registry, "dropped")).isZero();
    }

    @Test
    void neverBlockingRecoverer_countsARecordItDropsOutright() {
        doThrow(new IllegalStateException("Topic common-topic.DLT not present in metadata"))
                .when(delegate).accept(any(), any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerRecordRecoverer wrapped = KafkaConfig.neverBlockingRecoverer(delegate, registry);

        wrapped.accept(record(), new IllegalStateException("delivery failed"));

        // The outcome that must page someone: the notification is gone, not parked.
        assertThat(counterValue(registry, "dropped")).isEqualTo(1.0);
        assertThat(counterValue(registry, "deadlettered")).isZero();
    }

    @Test
    void neverBlockingRecoverer_stillWorksWithoutAMeterRegistry() {
        // Slice tests build the container factory with no metrics infrastructure present.
        ConsumerRecordRecoverer wrapped = KafkaConfig.neverBlockingRecoverer(delegate, null);

        assertThatCode(() -> wrapped.accept(record(), new IllegalStateException("boom")))
                .doesNotThrowAnyException();
    }

    private static double counterValue(SimpleMeterRegistry registry, String outcome) {
        var counter = registry.find(KafkaConfig.DEADLETTER_COUNTER).tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
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

    // ───────────────────────── listener concurrency ─────────────────────────

    /**
     * A single consumer thread is what lets a daily-report batch — one event per officer, each a
     * PDF render plus Glific calls throttled 500ms apart — hold up a login OTP until it expires.
     * The value is only effective up to common-topic's partition count, but the container must at
     * least ask for more than one.
     */
    @Test
    void kafkaListenerContainerFactory_drainsCommonTopicOnMoreThanOneThread() {
        KafkaConfig config = new KafkaConfig(new SimpleObjectProvider<>(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "groupId", "message-service-group");
        ReflectionTestUtils.setField(config, "listenerConcurrency", 3);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.kafkaListenerContainerFactory();

        assertThat(factory.getContainerProperties()).isNotNull();
        assertThat((Integer) ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(3);
    }

    // ───────────────────────── OTP topic isolation ─────────────────────────

    /**
     * The OTP container must never retry. The common-topic ladder's last rung lands ~130s out, past
     * the 60s resend cooldown, and {@code OtpService.requestOtp} revokes the previous code when the
     * user resends — so a redelivery hands them a dead code that burns a verification attempt.
     */
    @Test
    void otpBackOff_allowsNoRetriesAtAll() {
        FixedBackOff backOff = KafkaConfig.otpBackOff();

        assertThat(backOff.getMaxAttempts()).isZero();
    }

    @Test
    void otpListenerContainerFactory_runsOnItsOwnThreadsIndependentOfCommonTopic() {
        KafkaConfig config = new KafkaConfig(new SimpleObjectProvider<>(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "groupId", "message-service-group");
        ReflectionTestUtils.setField(config, "listenerConcurrency", 3);
        ReflectionTestUtils.setField(config, "otpListenerConcurrency", 2);

        // Separate containers are the point: a daily-report batch on common-topic must not be able
        // to occupy the threads a login OTP needs.
        assertThat((Integer) ReflectionTestUtils.getField(config.otpListenerContainerFactory(), "concurrency"))
                .isEqualTo(2);
        assertThat((Integer) ReflectionTestUtils.getField(config.kafkaListenerContainerFactory(), "concurrency"))
                .isEqualTo(3);
    }

    @Test
    void otpTopic_isDeclaredSoABrokerWithAutoCreateDisabledStillGetsIt() {
        KafkaConfig config = new KafkaConfig(new SimpleObjectProvider<>(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(config, "otpTopicPartitions", 3);
        ReflectionTestUtils.setField(config, "otpTopicReplicas", (short) 1);

        NewTopic topic = config.otpTopic();

        assertThat(topic.name()).isEqualTo(KafkaConfig.OTP_TOPIC);
        assertThat(topic.numPartitions()).isEqualTo(3);
    }

    @Test
    void kafkaListenerContainerFactory_honoursAConfiguredConcurrency() {
        KafkaConfig config = new KafkaConfig(new SimpleObjectProvider<>(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "groupId", "message-service-group");
        ReflectionTestUtils.setField(config, "listenerConcurrency", 1);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.kafkaListenerContainerFactory();

        // Deployments on a 1-partition topic can pin it back to 1 without a rebuild.
        assertThat((Integer) ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(1);
    }
}
