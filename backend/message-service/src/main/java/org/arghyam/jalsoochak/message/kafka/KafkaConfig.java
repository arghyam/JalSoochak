package org.arghyam.jalsoochak.message.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Slf4j
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    // ── Producer ──────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── Consumer ──────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        ExponentialBackOff backOff = new ExponentialBackOff(10_000L, 2.0);
        backOff.setMaxInterval(60_000L);      // cap at 60s per retry
        // 4 retries — 10s, 20s, 40s, 60s (130s of waiting) — then the recoverer runs. The limit is
        // compared against the elapsed time *before* each interval is handed out, so a 90s budget
        // still permits the 60s attempt that starts at 70s. Retries block this partition, and
        // common-topic carries every event type, so keep the total well under
        // max.poll.interval.ms (600s).
        backOff.setMaxElapsedTime(90_000L);
        // Route exhausted messages to <topic>.DLT rather than silently dropping them
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                neverBlockingRecoverer(new DeadLetterPublishingRecoverer(kafkaTemplate())), backOff);
        // Deserialization failures are permanent — skip retries and go straight to DLT
        errorHandler.addNotRetryableExceptions(DeserializationException.class);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    /**
     * Wraps the dead-letter recoverer so that a failed DLT publish cannot turn bounded retries into an
     * endless loop.
     *
     * <p>When the recoverer throws — most commonly because {@code <topic>.DLT} does not exist on a
     * broker with {@code auto.create.topics.enable=false} — {@link DefaultErrorHandler} logs
     * "Error handler threw an exception" and seeks back to the failed offset. The record is then
     * re-consumed from the top, forever. For a listener whose handlers have external side effects that
     * is not a retry, it is a broadcast: every pass around the loop re-ran the notification handler and
     * sent the officer another WhatsApp message.</p>
     *
     * <p>Swallowing the publish failure lets the offset commit, so the poison record is dropped after
     * its normal retry budget. The full payload is logged at ERROR with its topic/partition/offset,
     * which is the only copy left — replay it from there if the event still matters.</p>
     */
    static ConsumerRecordRecoverer neverBlockingRecoverer(ConsumerRecordRecoverer delegate) {
        return (consumerRecord, exception) -> {
            try {
                delegate.accept(consumerRecord, exception);
            } catch (Exception dltFailure) {
                log.error("[Kafka] Dead-letter publish failed for {}-{}@{}; dropping the record to avoid an"
                                + " endless redelivery loop (handlers with external side effects would re-run"
                                + " on every pass). Original failure: {}. DLT failure: {}. Payload: {}",
                        consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset(),
                        exception == null ? "unknown" : exception.getMessage(),
                        dltFailure.getMessage(), consumerRecord.value(), dltFailure);
            }
        };
    }
}
