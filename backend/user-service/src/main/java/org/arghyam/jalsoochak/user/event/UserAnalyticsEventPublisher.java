package org.arghyam.jalsoochak.user.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.kafka.KafkaProducer;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes user lifecycle events to {@code user-service-topic} after the current
 * DB transaction commits, preventing events for rolled-back operations.
 *
 * <p>If no transaction is active (e.g. tests or async contexts), the event is
 * published immediately.</p>
 *
 * <p><strong>Fire-and-forget:</strong> publish failures are logged as warnings and
 * counted via the {@code user.analytics.publish.failures} Micrometer counter, but
 * are not retried. Analytics loss is therefore possible if Kafka is unavailable.
 * For stricter delivery guarantees, consider routing failed payloads to a DLQ topic.</p>
 */
@Component
@Slf4j
public class UserAnalyticsEventPublisher {

    private static final String USER_TOPIC = "user-service-topic";

    private final KafkaProducer kafkaProducer;
    private final Counter publishFailureCounter;

    public UserAnalyticsEventPublisher(KafkaProducer kafkaProducer, MeterRegistry meterRegistry) {
        this.kafkaProducer = kafkaProducer;
        this.publishFailureCounter = Counter.builder("user.analytics.publish.failures")
                .description("Number of user analytics events that failed to publish to Kafka")
                .register(meterRegistry);
    }

    public void publishUserCreatedAfterCommit(AdminUserRow user, String title) {
        publishAfterCommit(buildPayload("USER_CREATED", user, title));
    }

    public void publishUserUpdatedAfterCommit(AdminUserRow user) {
        publishAfterCommit(buildPayload("USER_UPDATED", user, null));
    }

    /**
     * Variant for tenant-schema users (e.g. staff role updates) where an {@code AdminUserRow}
     * is not available. The {@code uuid} (Keycloak UUID) is the globally-unique identifier
     * for the user across tenants.
     */
    public void publishStaffUserUpdatedAfterCommit(Long userId, Integer tenantId, Integer userType,
                                                   String uuid, String email, int status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "USER_UPDATED");
        payload.put("userId", userId);
        payload.put("tenantId", tenantId);
        payload.put("email", email);
        payload.put("userType", userType);
        payload.put("uuid", uuid);
        payload.put("status", status);
        publishAfterCommit(payload);
    }

    public void publishUserUpdatedAfterCommit(
            Long userId,
            Integer tenantId,
            Integer userType,
            UUID uuid,
            String email,
            String title,
            Integer status
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "USER_UPDATED");
        payload.put("userId", userId);
        payload.put("tenantId", tenantId);
        payload.put("email", email);
        payload.put("userType", userType);
        payload.put("uuid", uuid);
        payload.put("title", title);
        payload.put("status", status);
        publishAfterCommit(payload);
    }

    public void publishUserSchemeMappingsReplacedAfterCommit(
            Long userId,
            Integer tenantId,
            UUID userUuid,
            List<Integer> schemeIds
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "USER_SCHEME_MAPPINGS_REPLACED");
        payload.put("userId", userId);
        payload.put("tenantId", tenantId);
        payload.put("userUuid", userUuid);
        payload.put("schemeIds", schemeIds);
        payload.put("status", 1);
        publishAfterCommit(payload);
    }

    private Map<String, Object> buildPayload(String eventType, AdminUserRow user, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("userId", user.id());
        payload.put("tenantId", user.tenantId());
        payload.put("email", user.email());
        payload.put("userType", user.adminLevel());
        payload.put("uuid", user.uuid());
        payload.put("status", user.status().code);
        if (title != null) payload.put("title", title);
        return payload;
    }

    private void publishAfterCommit(Map<String, Object> payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublish(payload);
                }
            });
        } else {
            doPublish(payload);
        }
    }

    private void doPublish(Map<String, Object> payload) {
        boolean ok = kafkaProducer.publishJson(USER_TOPIC, payload);
        if (!ok) {
            publishFailureCounter.increment();
            log.warn("[user-analytics] Failed to publish {} event to topic={}", payload.get("eventType"), USER_TOPIC);
        }
    }
}
