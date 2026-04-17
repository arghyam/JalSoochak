package org.arghyam.jalsoochak.user.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class KafkaConfigTest {

    private KafkaConfig kafkaConfig;

    @BeforeEach
    void setUp() {
        kafkaConfig = new KafkaConfig();
        ReflectionTestUtils.setField(kafkaConfig, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(kafkaConfig, "groupId", "test-group");
    }

    @Test
    void testProducerFactory() {
        ProducerFactory<String, String> producerFactory = kafkaConfig.producerFactory();
        
        assertNotNull(producerFactory);
        assertTrue(producerFactory instanceof DefaultKafkaProducerFactory);
        
        // Test producer configuration
        Map<String, Object> configs = ((DefaultKafkaProducerFactory<String, String>) producerFactory).getConfigurationProperties();
        
        assertEquals("localhost:9092", configs.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals(StringSerializer.class, configs.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(StringSerializer.class, configs.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
    }

    @Test
    void testKafkaTemplate() {
        KafkaTemplate<String, String> kafkaTemplate = kafkaConfig.kafkaTemplate();
        
        assertNotNull(kafkaTemplate);
        assertNotNull(kafkaTemplate.getProducerFactory());
        
        // Verify the producer factory is the same type as the one created by producerFactory()
        ProducerFactory<String, String> expectedFactory = kafkaConfig.producerFactory();
        assertEquals(expectedFactory.getClass(), kafkaTemplate.getProducerFactory().getClass());
    }

    @Test
    void testConsumerFactory() {
        ConsumerFactory<String, String> consumerFactory = kafkaConfig.consumerFactory();
        
        assertNotNull(consumerFactory);
        assertTrue(consumerFactory instanceof DefaultKafkaConsumerFactory);
        
        // Test consumer configuration
        Map<String, Object> configs = ((DefaultKafkaConsumerFactory<String, String>) consumerFactory).getConfigurationProperties();
        
        assertEquals("localhost:9092", configs.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("test-group", configs.get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals(StringDeserializer.class, configs.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals(StringDeserializer.class, configs.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        assertEquals("earliest", configs.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    void testKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = kafkaConfig.kafkaListenerContainerFactory();
        
        assertNotNull(factory);
        assertNotNull(factory.getConsumerFactory());
        
        // Verify the consumer factory is the same type as the one created by consumerFactory()
        ConsumerFactory<String, String> expectedFactory = kafkaConfig.consumerFactory();
        assertEquals(expectedFactory.getClass(), factory.getConsumerFactory().getClass());
    }

    @Test
    void testProducerFactoryConfigurations() {
        ProducerFactory<String, String> producerFactory = kafkaConfig.producerFactory();
        Map<String, Object> configs = ((DefaultKafkaProducerFactory<String, String>) producerFactory).getConfigurationProperties();
        
        // Test all required producer configurations are present
        assertTrue(configs.containsKey(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertTrue(configs.containsKey(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertTrue(configs.containsKey(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        
        // Test specific values
        assertEquals("localhost:9092", configs.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals(StringSerializer.class, configs.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(StringSerializer.class, configs.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
    }

    @Test
    void testConsumerFactoryConfigurations() {
        ConsumerFactory<String, String> consumerFactory = kafkaConfig.consumerFactory();
        Map<String, Object> configs = ((DefaultKafkaConsumerFactory<String, String>) consumerFactory).getConfigurationProperties();
        
        // Test all required consumer configurations are present
        assertTrue(configs.containsKey(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertTrue(configs.containsKey(ConsumerConfig.GROUP_ID_CONFIG));
        assertTrue(configs.containsKey(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertTrue(configs.containsKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        assertTrue(configs.containsKey(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        
        // Test specific values
        assertEquals("localhost:9092", configs.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("test-group", configs.get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals(StringDeserializer.class, configs.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals(StringDeserializer.class, configs.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        assertEquals("earliest", configs.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    void testKafkaListenerContainerFactoryProperties() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = kafkaConfig.kafkaListenerContainerFactory();
        
        assertNotNull(factory);
        assertNotNull(factory.getContainerProperties());
        
        // Test that the factory has the expected consumer factory
        ConsumerFactory<?, ?> consumerFactory = factory.getConsumerFactory();
        assertNotNull(consumerFactory);
        assertTrue(consumerFactory instanceof DefaultKafkaConsumerFactory);
    }
}
