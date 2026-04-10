package org.arghyam.jalsoochak.user.event;

import io.micrometer.core.instrument.MeterRegistry;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.kafka.KafkaProducer;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAnalyticsEventPublisherTest {

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private KafkaProducer kafkaProducer;

    @InjectMocks
    private UserAnalyticsEventPublisher publisher;

    private AdminUserRow buildUser(int tenantId, AdminUserStatus status) {
        return new AdminUserRow(
                42L, "kc-uuid-1234", "admin@state.gov", "91XXXXXXXXXX",
                tenantId, 2, "STATE_ADMIN", status, null, LocalDateTime.now()
        );
    }

    @BeforeEach
    void setUp() {
        lenient().when(meterRegistry.counter(any())).thenReturn(new io.micrometer.core.instrument.Counter() {
            @Override
            public void increment() {}

            @Override
            public void increment(double amount) {}

            @Override
            public double count() {
                return 0;
            }

            @Override
            public io.micrometer.core.instrument.Meter.Id getId() {
                return null;
            }
        });
    }

    @Test
    void publishUserCreatedAfterCommit_withActiveTransaction_registersAfterCommitHook() {
        AdminUserRow user = buildUser(3, AdminUserStatus.ACTIVE);

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                     mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);

            publisher.publishUserCreatedAfterCommit(user, "First Last");

            tsm.verify(() -> TransactionSynchronizationManager.registerSynchronization(
                    isA(TransactionSynchronization.class)));
            verify(kafkaProducer, never()).publishJson(any(), any());
        }
    }

    @Test
    void publishUserCreatedAfterCommit_noTransaction_publishesImmediately() {
        AdminUserRow user = buildUser(3, AdminUserStatus.ACTIVE);
        when(kafkaProducer.publishJson(eq("user-service-topic"), any())).thenReturn(true);

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                     mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(false);

            publisher.publishUserCreatedAfterCommit(user, "First Last");

            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(kafkaProducer).publishJson(eq("user-service-topic"), payloadCaptor.capture());

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
            assertThat(payload.get("eventType")).isEqualTo("USER_CREATED");
            assertThat(payload.get("userId")).isEqualTo(42L);
            assertThat(payload.get("tenantId")).isEqualTo(3);
            assertThat(payload.get("email")).isEqualTo("admin@state.gov");
            assertThat(payload.get("userType")).isEqualTo(2);
            assertThat(payload.get("uuid")).isEqualTo("kc-uuid-1234");
            assertThat(payload.get("status")).isEqualTo(AdminUserStatus.ACTIVE.code);
            assertThat(payload.get("title")).isEqualTo("First Last");
        }
    }

    @Test
    void publishUserUpdatedAfterCommit_noTransaction_publishesCorrectEventTypeAndOmitsTitle() {
        AdminUserRow user = buildUser(3, AdminUserStatus.ACTIVE);
        when(kafkaProducer.publishJson(eq("user-service-topic"), any())).thenReturn(true);

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                     mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(false);

            publisher.publishUserUpdatedAfterCommit(user);

            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(kafkaProducer).publishJson(eq("user-service-topic"), payloadCaptor.capture());

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
            assertThat(payload.get("eventType")).isEqualTo("USER_UPDATED");
            assertThat(payload.get("userId")).isEqualTo(42L);
            assertThat(payload.get("status")).isEqualTo(AdminUserStatus.ACTIVE.code);
            assertThat(payload).doesNotContainKey("title");
        }
    }

    @Test
    void publishStaffUserUpdatedAfterCommit_noTransaction_publishesCorrectPayload() {
        when(kafkaProducer.publishJson(eq("user-service-topic"), any())).thenReturn(true);

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                     mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(false);

            publisher.publishStaffUserUpdatedAfterCommit(99L, 5, 3, "kc-staff-uuid", "staff@state.gov", 1);

            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(kafkaProducer).publishJson(eq("user-service-topic"), payloadCaptor.capture());

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
            assertThat(payload.get("eventType")).isEqualTo("USER_UPDATED");
            assertThat(payload.get("userId")).isEqualTo(99L);
            assertThat(payload.get("tenantId")).isEqualTo(5);
            assertThat(payload.get("userType")).isEqualTo(3);
            assertThat(payload.get("uuid")).isEqualTo("kc-staff-uuid");
            assertThat(payload.get("email")).isEqualTo("staff@state.gov");
            assertThat(payload.get("status")).isEqualTo(1);
        }
    }
}
