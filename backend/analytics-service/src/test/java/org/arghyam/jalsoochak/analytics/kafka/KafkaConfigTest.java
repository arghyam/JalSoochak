package org.arghyam.jalsoochak.analytics.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.arghyam.jalsoochak.analytics.exception.MalformedEventException;
import org.junit.jupiter.api.Test;
import org.springframework.classify.Classifier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    private static KafkaConfig configWith(String bootstrapServers, String groupId) {
        KafkaConfig config = new KafkaConfig();
        setField(config, "bootstrapServers", bootstrapServers);
        setField(config, "groupId", groupId);
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void producerFactory_hasExpectedBootstrapAndSerializers() {
        KafkaConfig config = configWith("localhost:9092", "g1");

        ProducerFactory<String, String> pf = config.producerFactory();
        assertThat(pf).isInstanceOf(DefaultKafkaProducerFactory.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                ((DefaultKafkaProducerFactory<String, String>) pf).getConfigurationProperties();

        assertThat(props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("localhost:9092");
        assertThat(props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG))
                .isEqualTo(org.apache.kafka.common.serialization.StringSerializer.class);
        assertThat(props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
                .isEqualTo(org.apache.kafka.common.serialization.StringSerializer.class);
    }

    @Test
    void consumerFactory_hasExpectedBootstrapGroupAndDeserializers() {
        KafkaConfig config = configWith("localhost:9092", "analytics-group");

        ConsumerFactory<String, String> cf = config.consumerFactory();
        assertThat(cf).isInstanceOf(DefaultKafkaConsumerFactory.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                ((DefaultKafkaConsumerFactory<String, String>) cf).getConfigurationProperties();

        assertThat(props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("localhost:9092");
        assertThat(props.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("analytics-group");
        assertThat(props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(props.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(org.apache.kafka.common.serialization.StringDeserializer.class);
        assertThat(props.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(org.apache.kafka.common.serialization.StringDeserializer.class);
    }

    @Test
    void kafkaListenerContainerFactory_wiresConsumerFactoryAndErrorHandler() {
        KafkaConfig config = configWith("localhost:9092", "g1");
        ConsumerFactory<String, String> consumerFactory = config.consumerFactory();

        KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(config.producerFactory());
        DefaultErrorHandler errorHandler = config.kafkaErrorHandler(kafkaTemplate);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.kafkaListenerContainerFactory(consumerFactory, errorHandler);

        assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);
        Object configuredHandler = ReflectionTestUtils.getField(factory, "commonErrorHandler");
        assertThat(configuredHandler).isSameAs(errorHandler);
    }

    @Test
    void kafkaErrorHandler_sendsAMalformedEventToTheDltWithoutRetrying() {
        // The listeners wrap every failure in a RuntimeException, so the cause must be looked through.
        Throwable wrapped = new RuntimeException(new MalformedEventException("bad", null));

        assertThat(retryable(wrapped)).isFalse();
    }

    @Test
    void kafkaErrorHandler_stillRetriesAConcurrentMove() {
        // A reading moved to a scheme not locked succeeds once the event is replayed.
        Throwable wrapped = new RuntimeException(new ConcurrencyFailureException("moved"));

        assertThat(retryable(wrapped)).isTrue();
    }

    private static boolean retryable(Throwable thrown) {
        KafkaConfig config = configWith("localhost:9092", "g1");
        DefaultErrorHandler errorHandler =
                config.kafkaErrorHandler(new KafkaTemplate<>(config.producerFactory()));
        Classifier<Throwable, Boolean> classifier = ReflectionTestUtils.invokeMethod(errorHandler, "getClassifier");
        return classifier.classify(thrown);
    }
}

