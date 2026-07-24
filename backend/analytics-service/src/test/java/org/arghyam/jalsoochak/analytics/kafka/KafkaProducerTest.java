package org.arghyam.jalsoochak.analytics.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaProducerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private KafkaProducer producer;

    @Test
    void sendMessage_sendsToAnalyticsServiceTopic() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        producer.sendMessage("hello");

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), messageCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("analytics-service-topic");
        assertThat(messageCaptor.getValue()).isEqualTo("hello");
    }
}

