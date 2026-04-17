package org.arghyam.jalsoochak.user.event;

import org.arghyam.jalsoochak.user.kafka.KafkaProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserEventPublisher")
class UserEventPublisherTest {

    @Mock
    private KafkaProducer kafkaProducer;

    private UserEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new UserEventPublisher(kafkaProducer);
    }

    // --- publishWelcomeMessages ---

    @Nested
    @DisplayName("publishWelcomeMessages")
    class PublishWelcomeMessages {

        @Test
        @DisplayName("publishes SEND_WELCOME_MESSAGE event for each batch")
        void publishesWelcomeEvent() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), any())).thenReturn(true);

            publisher.publishWelcomeMessages("mp", 1, List.of("91987654321"));

            ArgumentCaptor<PumpOperatorMessagingEvent> captor =
                    ArgumentCaptor.forClass(PumpOperatorMessagingEvent.class);
            verify(kafkaProducer).publishJson(eq(UserEventPublisher.COMMON_TOPIC), captor.capture());

            assertThat(captor.getValue().getEventType()).isEqualTo("SEND_WELCOME_MESSAGE");
            assertThat(captor.getValue().getTenantCode()).isEqualTo("mp");
        }

        @Test
        @DisplayName("does nothing for null phone list")
        void doesNothingForNull() {
            publisher.publishWelcomeMessages("mp", 1, null);
            verify(kafkaProducer, never()).publishJson(any(), any());
        }

        @Test
        @DisplayName("does nothing for empty phone list")
        void doesNothingForEmpty() {
            publisher.publishWelcomeMessages("mp", 1, List.of());
            verify(kafkaProducer, never()).publishJson(any(), any());
        }
    }

    // --- publishAdminWelcomeMessages ---

    @Nested
    @DisplayName("publishAdminWelcomeMessages")
    class PublishAdminWelcomeMessages {

        @Test
        @DisplayName("publishes SEND_WELCOME_MESSAGE_ADMIN event")
        void publishesAdminWelcomeEvent() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), any())).thenReturn(true);

            publisher.publishAdminWelcomeMessages("tr", 2, List.of("91987654321"));

            ArgumentCaptor<PumpOperatorMessagingEvent> captor =
                    ArgumentCaptor.forClass(PumpOperatorMessagingEvent.class);
            verify(kafkaProducer).publishJson(eq(UserEventPublisher.COMMON_TOPIC), captor.capture());

            assertThat(captor.getValue().getEventType()).isEqualTo("SEND_WELCOME_MESSAGE_ADMIN");
            assertThat(captor.getValue().getTenantCode()).isEqualTo("tr");
            assertThat(captor.getValue().getTenantId()).isEqualTo(2);
        }

        @Test
        @DisplayName("does nothing for null or empty phone list")
        void doesNothingForNullOrEmpty() {
            publisher.publishAdminWelcomeMessages("tr", 2, null);
            publisher.publishAdminWelcomeMessages("tr", 2, List.of());
            verify(kafkaProducer, never()).publishJson(any(), any());
        }

        @Test
        @DisplayName("batches large phone lists into chunks of 1000")
        void batchesLargeList() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), any())).thenReturn(true);

            List<String> phones = new ArrayList<>();
            for (int i = 0; i < 2500; i++) {
                phones.add("9198765" + String.format("%05d", i));
            }

            publisher.publishAdminWelcomeMessages("mp", 1, phones);

            // 2500 phones → 3 batches (1000 + 1000 + 500)
            verify(kafkaProducer, times(3)).publishJson(eq(UserEventPublisher.COMMON_TOPIC), any());
        }
    }

    // --- publishPumpOperatorOnboardedAfterCommit ---

    @Nested
    @DisplayName("publishPumpOperatorOnboardedAfterCommit")
    class PublishPumpOperatorOnboarded {

        @Test
        @DisplayName("does nothing for null or empty phone list")
        void doesNothingForNullOrEmpty() {
            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en", null);
            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en", List.of());
            verify(kafkaProducer, never()).publishJson(any(), any());
        }
    }
}
