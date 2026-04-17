package org.arghyam.jalsoochak.user.event;

import org.arghyam.jalsoochak.user.kafka.KafkaProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserNotificationEventPublisher")
class UserNotificationEventPublisherTest {

    private static final String COMMON_TOPIC = "common-topic";

    @Mock private KafkaProducer kafkaProducer;

    private UserNotificationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new UserNotificationEventPublisher(kafkaProducer);
    }

    private static InviteEmailEvent anInviteEvent() {
        return InviteEmailEvent.builder().to("user@example.com").eventType("SEND_INVITE_EMAIL").build();
    }

    private static ResetPasswordEmailEvent aResetEvent() {
        return ResetPasswordEmailEvent.builder().to("reset@example.com").eventType("SEND_PASSWORD_RESET_EMAIL").build();
    }

    private static SendLoginOtpEvent anOtpEvent() {
        return SendLoginOtpEvent.builder().officerPhoneNumber("91XXXXXXXXX1").eventType("SEND_LOGIN_OTP").otp("123456").build();
    }

    // ── publishInviteEmailAfterCommit ─────────────────────────────────────────

    @Nested
    @DisplayName("publishInviteEmailAfterCommit")
    class PublishInviteEmail {

        @Test
        @DisplayName("publishes immediately when no active transaction")
        void publishesImmediatelyWithoutTransaction() {
            InviteEmailEvent event = anInviteEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(true);

            publisher.publishInviteEmailAfterCommit(event);

            verify(kafkaProducer).publishJson(COMMON_TOPIC, event);
        }

        @Test
        @DisplayName("registers synchronization and publishes after commit when transaction is active")
        void registersAfterCommitWhenTransactionActive() {
            InviteEmailEvent event = anInviteEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(true);

            List<TransactionSynchronization> captured = new ArrayList<>();
            try (MockedStatic<TransactionSynchronizationManager> tsm =
                         mockStatic(TransactionSynchronizationManager.class)) {
                tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
                tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                        .thenAnswer(inv -> { captured.add(inv.getArgument(0)); return null; });

                publisher.publishInviteEmailAfterCommit(event);

                verify(kafkaProducer, never()).publishJson(any(), any());
                assertThat(captured).hasSize(1);

                captured.get(0).afterCommit();
                verify(kafkaProducer).publishJson(COMMON_TOPIC, event);
            }
        }

        @Test
        @DisplayName("handles Kafka publish failure gracefully (returns false)")
        void kafkaFailureHandledGracefully() {
            InviteEmailEvent event = anInviteEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(false);

            publisher.publishInviteEmailAfterCommit(event); // must not throw
        }
    }

    // ── publishResetPasswordEmailAfterCommit ──────────────────────────────────

    @Nested
    @DisplayName("publishResetPasswordEmailAfterCommit")
    class PublishResetPasswordEmail {

        @Test
        @DisplayName("publishes immediately when no active transaction")
        void publishesImmediately() {
            ResetPasswordEmailEvent event = aResetEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(true);

            publisher.publishResetPasswordEmailAfterCommit(event);

            verify(kafkaProducer).publishJson(COMMON_TOPIC, event);
        }

        @Test
        @DisplayName("registers synchronization when transaction is active")
        void registersWithActiveTransaction() {
            ResetPasswordEmailEvent event = aResetEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(true);

            List<TransactionSynchronization> captured = new ArrayList<>();
            try (MockedStatic<TransactionSynchronizationManager> tsm =
                         mockStatic(TransactionSynchronizationManager.class)) {
                tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
                tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                        .thenAnswer(inv -> { captured.add(inv.getArgument(0)); return null; });

                publisher.publishResetPasswordEmailAfterCommit(event);

                verify(kafkaProducer, never()).publishJson(any(), any());
                captured.get(0).afterCommit();
                verify(kafkaProducer).publishJson(COMMON_TOPIC, event);
            }
        }

        @Test
        @DisplayName("handles Kafka publish failure gracefully")
        void kafkaFailureHandledGracefully() {
            ResetPasswordEmailEvent event = aResetEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(false);

            publisher.publishResetPasswordEmailAfterCommit(event);
        }
    }

    // ── publishLoginOtpAfterCommit ────────────────────────────────────────────

    @Nested
    @DisplayName("publishLoginOtpAfterCommit")
    class PublishLoginOtp {

        @Test
        @DisplayName("publishes immediately (single-arg overload) when no active transaction")
        void publishesImmediatelySingleArg() {
            SendLoginOtpEvent event = anOtpEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(true);

            publisher.publishLoginOtpAfterCommit(event);

            verify(kafkaProducer).publishJson(COMMON_TOPIC, event);
        }

        @Test
        @DisplayName("publishes immediately (three-arg overload) with staffUserId and tenantCode")
        void publishesImmediatelyWithStaffInfo() {
            SendLoginOtpEvent event = anOtpEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(true);

            publisher.publishLoginOtpAfterCommit(event, 99L, "MP");

            verify(kafkaProducer).publishJson(COMMON_TOPIC, event);
        }

        @Test
        @DisplayName("registers synchronization when transaction is active (three-arg overload)")
        void registersWithActiveTransaction() {
            SendLoginOtpEvent event = anOtpEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(true);

            List<TransactionSynchronization> captured = new ArrayList<>();
            try (MockedStatic<TransactionSynchronizationManager> tsm =
                         mockStatic(TransactionSynchronizationManager.class)) {
                tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
                tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                        .thenAnswer(inv -> { captured.add(inv.getArgument(0)); return null; });

                publisher.publishLoginOtpAfterCommit(event, 10L, "MP");

                verify(kafkaProducer, never()).publishJson(any(), any());
                captured.get(0).afterCommit();
                verify(kafkaProducer).publishJson(COMMON_TOPIC, event);
            }
        }

        @Test
        @DisplayName("handles Kafka failure gracefully when no transaction (returns false)")
        void kafkaFailureNoTransaction() {
            SendLoginOtpEvent event = anOtpEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(false);

            publisher.publishLoginOtpAfterCommit(event, null, null); // must not throw
        }

        @Test
        @DisplayName("handles after-commit Kafka failure gracefully")
        void afterCommitKafkaFailure() {
            SendLoginOtpEvent event = anOtpEvent();
            when(kafkaProducer.publishJson(eq(COMMON_TOPIC), eq(event))).thenReturn(false);

            List<TransactionSynchronization> captured = new ArrayList<>();
            try (MockedStatic<TransactionSynchronizationManager> tsm =
                         mockStatic(TransactionSynchronizationManager.class)) {
                tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
                tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                        .thenAnswer(inv -> { captured.add(inv.getArgument(0)); return null; });

                publisher.publishLoginOtpAfterCommit(event, 5L, "MP");
                captured.get(0).afterCommit(); // must not throw even when Kafka returns false
            }
        }
    }

    // ── event payload propagation ─────────────────────────────────────────────

    @Nested
    @DisplayName("event payload propagation")
    class PayloadPropagation {

        @Test
        @DisplayName("the exact event object is passed to kafkaProducer.publishJson")
        void passesExactEvent() {
            InviteEmailEvent event = anInviteEvent();
            when(kafkaProducer.publishJson(any(), any())).thenReturn(true);

            publisher.publishInviteEmailAfterCommit(event);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(kafkaProducer).publishJson(eq(COMMON_TOPIC), captor.capture());
            assertThat(captor.getValue()).isSameAs(event);
        }
    }
}
