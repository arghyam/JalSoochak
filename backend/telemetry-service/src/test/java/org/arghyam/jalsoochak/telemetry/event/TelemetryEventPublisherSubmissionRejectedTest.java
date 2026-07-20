package org.arghyam.jalsoochak.telemetry.event;

import org.arghyam.jalsoochak.telemetry.dto.event.SubmissionRejectedEvent;
import org.arghyam.jalsoochak.telemetry.kafka.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// REPORTED-METRIC
@ExtendWith(MockitoExtension.class)
class TelemetryEventPublisherSubmissionRejectedTest {

    @Mock
    private KafkaProducer kafkaProducer;

    @Test
    void publishSubmissionRejected_publishesEventWithSubmittedIds() {
        TelemetryEventPublisher publisher = new TelemetryEventPublisher(kafkaProducer);
        when(kafkaProducer.publishJson(eq(TelemetryEventPublisher.TOPIC), any())).thenReturn(true);

        publisher.publishSubmissionRejected(17, "6121849", "6121849", "phv", "validation: phone must not be blank");

        ArgumentCaptor<SubmissionRejectedEvent> captor = ArgumentCaptor.forClass(SubmissionRejectedEvent.class);
        verify(kafkaProducer).publishJson(eq(TelemetryEventPublisher.TOPIC), captor.capture());
        SubmissionRejectedEvent event = captor.getValue();
        assertEquals(TelemetryEventPublisher.EVENT_SUBMISSION_REJECTED, event.getEventType());
        assertEquals(17, event.getTenantId());
        assertEquals("6121849", event.getSubmittedStateSchemeId());
        assertEquals("6121849", event.getSubmittedCentreSchemeId());
        assertEquals("phv", event.getSubmittedPhoneHash());
        assertEquals("validation: phone must not be blank", event.getReason());
        assertNotNull(event.getAttemptedAt());
    }
}
