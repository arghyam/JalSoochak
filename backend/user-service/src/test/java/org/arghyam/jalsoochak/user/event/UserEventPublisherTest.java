package org.arghyam.jalsoochak.user.event;

import org.arghyam.jalsoochak.user.kafka.KafkaProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        @DisplayName("batches large phone lists into chunks of 100")
        void batchesLargeList() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), any())).thenReturn(true);

            publisher.publishAdminWelcomeMessages("mp", 1, phones(250));

            // 250 phones → 3 batches (100 + 100 + 50)
            verify(kafkaProducer, times(3)).publishJson(eq(UserEventPublisher.COMMON_TOPIC), any());
        }
    }

    // --- publishPumpOperatorOnboardedAfterCommit ---

    @Nested
    @DisplayName("publishPumpOperatorOnboardedAfterCommit")
    class PublishPumpOperatorOnboarded {

        /** A re-check that finds every language phone still to be sent. */
        private static final UnaryOperator<List<String>> KEEP_ALL = UnaryOperator.identity();

        @AfterEach
        void cleanUpTransactionSync() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        private List<PumpOperatorMessagingEvent> awaitPublished(String key, int count) {
            ArgumentCaptor<PumpOperatorMessagingEvent> captor =
                    ArgumentCaptor.forClass(PumpOperatorMessagingEvent.class);
            verify(kafkaProducer, timeout(2000).times(count))
                    .publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq(key), captor.capture());
            return captor.getAllValues();
        }

        @Test
        @DisplayName("does nothing for null or empty phone lists")
        void doesNothingForNullOrEmpty() {
            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en", null, KEEP_ALL, null);
            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en", List.of(), KEEP_ALL, List.of());
            verifyNoInteractions(kafkaProducer);
        }

        @Test
        @DisplayName("publishes UPDATE_USER_LANGUAGE before SEND_WELCOME_MESSAGE, keyed by tenant, when no transaction active")
        void publishesLanguageUpdateBeforeWelcome() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("mp"), any())).thenReturn(true);

            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en",
                    List.of("91XXXXXXXXX1"), KEEP_ALL, List.of("91XXXXXXXXX1"));

            List<PumpOperatorMessagingEvent> events = awaitPublished("mp", 2);
            assertThat(events).extracting(PumpOperatorMessagingEvent::getEventType)
                    .containsExactly("UPDATE_USER_LANGUAGE", "SEND_WELCOME_MESSAGE");
            assertThat(events).extracting(PumpOperatorMessagingEvent::getWhatsappLanguageId)
                    .containsOnly("en");
        }

        @Test
        @DisplayName("splits each list into events of at most 100 phones, all language updates first")
        void batchesLargeListsIntoEventsOf100() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("mp"), any())).thenReturn(true);

            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en", phones(150), KEEP_ALL, phones(250));

            List<PumpOperatorMessagingEvent> events = awaitPublished("mp", 5);
            assertThat(events).extracting(PumpOperatorMessagingEvent::getEventType).containsExactly(
                    "UPDATE_USER_LANGUAGE", "UPDATE_USER_LANGUAGE",
                    "SEND_WELCOME_MESSAGE", "SEND_WELCOME_MESSAGE", "SEND_WELCOME_MESSAGE");
            assertThat(events).extracting(e -> e.getPumpOperatorPhones().size())
                    .containsExactly(100, 50, 100, 100, 50);
        }

        @Test
        @DisplayName("sends the language update only to the operators whose language was set")
        void sendsLanguageUpdateOnlyToItsPhones() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("mp"), any())).thenReturn(true);

            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en",
                    List.of("91XXXXXXXXX1"), KEEP_ALL, List.of("91XXXXXXXXX1", "91XXXXXXXXX2"));

            List<PumpOperatorMessagingEvent> events = awaitPublished("mp", 2);
            assertThat(events.get(0).getPumpOperatorPhones()).containsExactly("91XXXXXXXXX1");
            assertThat(events.get(1).getPumpOperatorPhones()).containsExactly("91XXXXXXXXX1", "91XXXXXXXXX2");
        }

        @Test
        @DisplayName("re-checks the language phones after commit and sends the update only to those it keeps")
        void reChecksLanguagePhonesAfterCommit() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("mp"), any())).thenReturn(true);
            AtomicBoolean committed = new AtomicBoolean(false);
            AtomicBoolean reCheckedAfterCommit = new AtomicBoolean(false);
            UnaryOperator<List<String>> dropSecond = phones -> {
                reCheckedAfterCommit.set(committed.get());
                return List.of("91XXXXXXXXX1");
            };
            TransactionSynchronizationManager.initSynchronization();

            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en",
                    List.of("91XXXXXXXXX1", "91XXXXXXXXX2"), dropSecond, List.of("91XXXXXXXXX1", "91XXXXXXXXX2"));
            committed.set(true);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            List<PumpOperatorMessagingEvent> events = awaitPublished("mp", 2);
            assertThat(reCheckedAfterCommit).isTrue();
            assertThat(events.get(0).getEventType()).isEqualTo("UPDATE_USER_LANGUAGE");
            assertThat(events.get(0).getPumpOperatorPhones()).containsExactly("91XXXXXXXXX1");
            assertThat(events.get(1).getPumpOperatorPhones()).containsExactly("91XXXXXXXXX1", "91XXXXXXXXX2");
        }

        @Test
        @DisplayName("sends no language update when the re-check keeps no phone, but still the welcome")
        void sendsOnlyWelcomeWhenReCheckKeepsNoPhone() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("mp"), any())).thenReturn(true);

            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en",
                    List.of("91XXXXXXXXX1"), phones -> List.of(), List.of("91XXXXXXXXX1"));

            assertThat(awaitPublished("mp", 1)).extracting(PumpOperatorMessagingEvent::getEventType)
                    .containsExactly("SEND_WELCOME_MESSAGE");
        }

        @Test
        @DisplayName("sends no language update when the re-check fails, but still the welcome")
        void sendsOnlyWelcomeWhenReCheckFails() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("mp"), any())).thenReturn(true);

            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en", List.of("91XXXXXXXXX1"),
                    phones -> { throw new IllegalStateException("db down"); }, List.of("91XXXXXXXXX1"));

            assertThat(awaitPublished("mp", 1)).extracting(PumpOperatorMessagingEvent::getEventType)
                    .containsExactly("SEND_WELCOME_MESSAGE");
        }

        @Test
        @DisplayName("publishes only the welcome when no operator's language was set")
        void publishesOnlyWelcome() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("mp"), any())).thenReturn(true);

            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en", List.of(), KEEP_ALL, List.of("91XXXXXXXXX1"));

            assertThat(awaitPublished("mp", 1)).extracting(PumpOperatorMessagingEvent::getEventType)
                    .containsExactly("SEND_WELCOME_MESSAGE");
        }

        @Test
        @DisplayName("publishes only the language update when there is no one to welcome")
        void publishesOnlyLanguageUpdate() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("mp"), any())).thenReturn(true);

            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en", List.of("91XXXXXXXXX1"), KEEP_ALL, List.of());

            assertThat(awaitPublished("mp", 1)).extracting(PumpOperatorMessagingEvent::getEventType)
                    .containsExactly("UPDATE_USER_LANGUAGE");
        }

        @Test
        @DisplayName("registers afterCommit synchronization when transaction is active and fires on commit")
        void registersAfterCommitWhenTransactionActive() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("mp"), any())).thenReturn(true);

            // Simulate an active transaction synchronization context
            TransactionSynchronizationManager.initSynchronization();

            publisher.publishPumpOperatorOnboardedAfterCommit("mp", 1, "en",
                    List.of("91XXXXXXXXX1"), KEEP_ALL, List.of("91XXXXXXXXX1"));

            // Before commit fires — events must NOT have been published yet
            verifyNoInteractions(kafkaProducer);

            // Fire afterCommit on all registered synchronizations
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            // Now events should be published asynchronously
            assertThat(awaitPublished("mp", 2)).extracting(PumpOperatorMessagingEvent::getEventType)
                    .containsExactly("UPDATE_USER_LANGUAGE", "SEND_WELCOME_MESSAGE");
        }

        @Test
        @DisplayName("logs warning and publishes immediately when no transaction synchronization is active")
        void publishesImmediatelyWhenNoTransactionSync() {
            when(kafkaProducer.publishJson(eq(UserEventPublisher.COMMON_TOPIC), eq("tr"), any())).thenReturn(true);

            // No TransactionSynchronizationManager.initSynchronization() — tests the else branch
            publisher.publishPumpOperatorOnboardedAfterCommit("tr", 2, "hi",
                    List.of("91XXXXXXXXX2"), KEEP_ALL, List.of("91XXXXXXXXX2"));

            awaitPublished("tr", 2);
        }
    }

    private static List<String> phones(int count) {
        List<String> phones = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            phones.add("91XXXXX" + String.format("%05d", i));
        }
        return phones;
    }
}
