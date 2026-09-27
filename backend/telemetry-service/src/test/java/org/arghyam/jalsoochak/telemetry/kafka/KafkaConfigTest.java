package org.arghyam.jalsoochak.telemetry.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka producer/consumer wiring. Telemetry publishes and consumes plain JSON strings, so both ends
 * must stay on the String (de)serializers, and the consumer must start from the earliest offset so a
 * restarted service does not silently drop backlog.
 */
@DisplayName("KafkaConfig")
class KafkaConfigTest {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final String GROUP_ID = "telemetry-service";

    private KafkaConfig config;

    @BeforeEach
    void setUp() {
        config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", BOOTSTRAP);
        ReflectionTestUtils.setField(config, "groupId", GROUP_ID);
    }

    @Test
    void producerFactoryUsesStringSerializersAgainstTheConfiguredBroker() {
        ProducerFactory<String, String> factory = config.producerFactory();

        assertThat(factory).isInstanceOf(DefaultKafkaProducerFactory.class);
        Map<String, Object> props = factory.getConfigurationProperties();
        assertThat(props).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        assertThat(props).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(props).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    }

    @Test
    void kafkaTemplateIsBackedByTheProducerFactory() {
        KafkaTemplate<String, String> template = config.kafkaTemplate();

        assertThat(template).isNotNull();
        assertThat(template.getProducerFactory().getConfigurationProperties())
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
    }

    @Test
    void consumerFactoryUsesStringDeserializersAndTheConfiguredGroup() {
        ConsumerFactory<String, String> factory = config.consumerFactory();

        assertThat(factory).isInstanceOf(DefaultKafkaConsumerFactory.class);
        Map<String, Object> props = factory.getConfigurationProperties();
        assertThat(props).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        assertThat(props).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        assertThat(props).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(props).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    }

    @Test
    void consumerStartsFromTheEarliestOffsetSoBacklogIsNotDropped() {
        assertThat(config.consumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    @Test
    void listenerContainerFactoryIsBackedByTheConsumerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = config.kafkaListenerContainerFactory();

        assertThat(factory).isNotNull();
        assertThat(factory.getConsumerFactory()).isNotNull();
        assertThat(factory.getConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
    }
}
