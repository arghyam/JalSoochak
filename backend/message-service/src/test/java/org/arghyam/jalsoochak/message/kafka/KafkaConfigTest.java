package org.arghyam.jalsoochak.message.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link KafkaConfig#neverBlockingRecoverer}.
 *
 * <p>A recoverer that throws makes {@code DefaultErrorHandler} seek back to the failed offset, which
 * re-runs the listener forever. For handlers that send WhatsApp messages that loop is not a retry, it
 * is a broadcast — the incident behind this test had an officer receiving the same daily report over
 * and over while the dead-letter publish kept failing.</p>
 */
class KafkaConfigTest {

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("common-topic", 0, 2831582L, "key", "{\"eventType\":\"DAILY_REPORT_KPIS\"}");
    }

    @Test
    void neverBlockingRecoverer_passesTheRecordToTheDelegate() {
        AtomicInteger calls = new AtomicInteger();
        ConsumerRecordRecoverer wrapped = KafkaConfig.neverBlockingRecoverer(
                (rec, ex) -> calls.incrementAndGet());

        wrapped.accept(record(), new IllegalStateException("delivery failed"));

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void neverBlockingRecoverer_swallowsADeadLetterPublishFailure() {
        ConsumerRecordRecoverer wrapped = KafkaConfig.neverBlockingRecoverer((rec, ex) -> {
            throw new IllegalStateException("Topic common-topic.DLT not present in metadata");
        });

        // Must not propagate: a thrown recoverer is what turns the retry budget into an endless loop.
        assertThatCode(() -> wrapped.accept(record(), new IllegalStateException("delivery failed")))
                .doesNotThrowAnyException();
    }

    @Test
    void neverBlockingRecoverer_toleratesANullOriginalException() {
        ConsumerRecordRecoverer wrapped = KafkaConfig.neverBlockingRecoverer((rec, ex) -> {
            throw new IllegalStateException("DLT unavailable");
        });

        assertThatCode(() -> wrapped.accept(record(), null)).doesNotThrowAnyException();
    }
}
