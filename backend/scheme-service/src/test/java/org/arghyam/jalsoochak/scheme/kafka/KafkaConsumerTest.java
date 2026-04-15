package org.arghyam.jalsoochak.scheme.kafka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class KafkaConsumerTest {

    @Test
    void consume_doesNotThrow() {
        KafkaConsumer consumer = new KafkaConsumer();
        assertThatCode(() -> consumer.consume("hello")).doesNotThrowAnyException();
    }
}
