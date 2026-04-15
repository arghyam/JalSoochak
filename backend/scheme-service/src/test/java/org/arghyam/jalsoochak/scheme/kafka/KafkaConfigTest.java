package org.arghyam.jalsoochak.scheme.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
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

class KafkaConfigTest {

    @Test
    void buildsProducerAndConsumerFactories() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "groupId", "scheme-group");

        ProducerFactory<String, String> producerFactory = config.producerFactory();
        ConsumerFactory<String, String> consumerFactory = config.consumerFactory();
        KafkaTemplate<String, String> kafkaTemplate = config.kafkaTemplate();
        ConcurrentKafkaListenerContainerFactory<String, String> listenerFactory = config.kafkaListenerContainerFactory();

        assertThat(producerFactory).isInstanceOf(DefaultKafkaProducerFactory.class);
        assertThat(consumerFactory).isInstanceOf(DefaultKafkaConsumerFactory.class);
        assertThat(kafkaTemplate).isNotNull();
        assertThat(listenerFactory).isNotNull();

        Map<String, Object> producerConfig = ((DefaultKafkaProducerFactory<String, String>) producerFactory).getConfigurationProperties();
        Map<String, Object> consumerConfig = ((DefaultKafkaConsumerFactory<String, String>) consumerFactory).getConfigurationProperties();

        assertThat(producerConfig.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("localhost:9092");
        assertThat(consumerConfig.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("localhost:9092");
        assertThat(consumerConfig.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("scheme-group");
        assertThat(consumerConfig.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
    }
}
