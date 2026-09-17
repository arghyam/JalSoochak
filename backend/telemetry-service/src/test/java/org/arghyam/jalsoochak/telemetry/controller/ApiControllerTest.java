package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.dto.SampleDTO;
import org.arghyam.jalsoochak.telemetry.kafka.KafkaProducer;
import org.arghyam.jalsoochak.telemetry.service.BusinessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service's baseline REST surface: a readings listing and a manual Kafka publish used for
 * connectivity checks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiController")
class ApiControllerTest {

    @Mock
    private BusinessService businessService;
    @Mock
    private KafkaProducer kafkaProducer;

    @InjectMocks
    private ApiController controller;

    @Test
    void getAllReadingsReturnsWhatTheServiceProvides() {
        List<SampleDTO> readings = List.of(new SampleDTO(), new SampleDTO());
        when(businessService.getAllReadings()).thenReturn(readings);

        var response = controller.getAllReadings();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(readings);
    }

    @Test
    void getAllReadingsReturnsAnEmptyListWhenThereAreNoReadings() {
        when(businessService.getAllReadings()).thenReturn(List.of());

        assertThat(controller.getAllReadings().getBody()).isEmpty();
    }

    @Test
    void publishMessageForwardsThePayloadToKafka() {
        var response = controller.publishMessage("hello");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("Message published to telemetry-service-topic");
        verify(kafkaProducer).sendMessage("hello");
    }

    @Test
    void publishMessageForwardsAnEmptyPayloadUnchanged() {
        controller.publishMessage("");

        verify(kafkaProducer).sendMessage("");
    }
}
