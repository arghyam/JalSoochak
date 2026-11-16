package org.arghyam.jalsoochak.user.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.kafka.KafkaProducer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventPublisher {

    public static final String COMMON_TOPIC = "common-topic";
    private static final int MAX_PHONES_PER_EVENT = 1000;

    private final KafkaProducer kafkaProducer;
    private final ExecutorService publishExecutor = Executors.newFixedThreadPool(4);

    @PreDestroy
    public void shutdown() {
        publishExecutor.shutdown();
    }

    /**
     * Publishes message-service compatible onboarding events after DB commit:
     *  - UPDATE_USER_LANGUAGE for the operators whose language the upload set
     *  - SEND_WELCOME_MESSAGE for the operators to welcome
     * Every event is keyed by the tenant code and the language updates go first, so message-service sets
     * an operator's language before it starts their welcome flow.
     */
    public void publishPumpOperatorOnboardedAfterCommit(
            String tenantCode,
            Integer tenantId,
            String whatsappLanguageId,
            List<String> languagePhones,
            List<String> welcomePhones
    ) {
        List<List<String>> languageBatches = partition(languagePhones, MAX_PHONES_PER_EVENT);
        List<List<String>> welcomeBatches = partition(welcomePhones, MAX_PHONES_PER_EVENT);
        if (languageBatches.isEmpty() && welcomeBatches.isEmpty()) {
            return;
        }

        log.info("[user-events] queued_after_commit count={} topic={} tenantCode={} tenantId={}"
                        + " languagePhones={} welcomePhones={}",
                languageBatches.size() + welcomeBatches.size(), COMMON_TOPIC, tenantCode, tenantId,
                languagePhones == null ? 0 : languagePhones.size(), welcomePhones == null ? 0 : welcomePhones.size());

        Runnable publish = () -> {
            String triggeredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
            for (List<String> phones : languageBatches) {
                publishOnboardingEvent("UPDATE_USER_LANGUAGE", tenantCode, tenantId, triggeredAt,
                        whatsappLanguageId, phones);
            }
            for (List<String> phones : welcomeBatches) {
                publishOnboardingEvent("SEND_WELCOME_MESSAGE", tenantCode, tenantId, triggeredAt,
                        whatsappLanguageId, phones);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Never block the request thread on Kafka metadata/broker availability.
                    CompletableFuture.runAsync(publish, publishExecutor)
                            .exceptionally(ex -> {
                                log.error("Async user event publish failed (best-effort): {}", ex.getMessage(), ex);
                                return null;
                            });
                }
            });
        } else {
            log.warn("No active transaction synchronization; publishing user events immediately");
            CompletableFuture.runAsync(publish, publishExecutor)
                    .exceptionally(ex -> {
                        log.error("Async user event publish failed (best-effort): {}", ex.getMessage(), ex);
                        return null;
                    });
        }
    }

    /**
     * Publishes welcome-message events immediately (no transaction coupling).
     * This is intended for bulk re-sends triggered by admin endpoints.
     */
    public void publishWelcomeMessages(
            String tenantCode,
            Integer tenantId,
            List<String> pumpOperatorPhones
    ) {
        if (pumpOperatorPhones == null || pumpOperatorPhones.isEmpty()) {
            return;
        }
        List<List<String>> batches = partition(pumpOperatorPhones, MAX_PHONES_PER_EVENT);
        log.info("[user-events] welcome_publish count={} topic={} tenantCode={} tenantId={} phones={}",
                batches.size(), COMMON_TOPIC, tenantCode, tenantId, pumpOperatorPhones.size());

        String triggeredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        for (List<String> phones : batches) {
            PumpOperatorMessagingEvent welcome = PumpOperatorMessagingEvent.builder()
                    .eventType("SEND_WELCOME_MESSAGE")
                    .tenantCode(tenantCode)
                    .tenantId(tenantId)
                    .triggeredAt(triggeredAt)
                    .whatsappLanguageId(null)
                    .pumpOperatorPhones(phones)
                    .build();

            boolean ok = kafkaProducer.publishJson(COMMON_TOPIC, welcome);
            if (!ok) {
                log.warn("[user-events] welcome_publish_failed tenantCode={} tenantId={} phones={}",
                        tenantCode, tenantId, phones.size());
            }
        }
    }

    /**
     * Publishes admin-triggered welcome-message events that should opt-in by phone.
     */
    public void publishAdminWelcomeMessages(
            String tenantCode,
            Integer tenantId,
            List<String> pumpOperatorPhones
    ) {
        if (pumpOperatorPhones == null || pumpOperatorPhones.isEmpty()) {
            return;
        }
        List<List<String>> batches = partition(pumpOperatorPhones, MAX_PHONES_PER_EVENT);
        log.info("[user-events] welcome_admin_publish count={} topic={} tenantCode={} tenantId={} phones={}",
                batches.size(), COMMON_TOPIC, tenantCode, tenantId, pumpOperatorPhones.size());

        String triggeredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        for (List<String> phones : batches) {
            PumpOperatorMessagingEvent welcome = PumpOperatorMessagingEvent.builder()
                    .eventType("SEND_WELCOME_MESSAGE_ADMIN")
                    .tenantCode(tenantCode)
                    .tenantId(tenantId)
                    .triggeredAt(triggeredAt)
                    .whatsappLanguageId(null)
                    .pumpOperatorPhones(phones)
                    .build();

            boolean ok = kafkaProducer.publishJson(COMMON_TOPIC, welcome);
            if (!ok) {
                log.warn("[user-events] welcome_admin_publish_failed tenantCode={} tenantId={} phones={}",
                        tenantCode, tenantId, phones.size());
            }
        }
    }

    private void publishOnboardingEvent(String eventType, String tenantCode, Integer tenantId,
                                        String triggeredAt, String whatsappLanguageId, List<String> phones) {
        PumpOperatorMessagingEvent event = PumpOperatorMessagingEvent.builder()
                .eventType(eventType)
                .tenantCode(tenantCode)
                .tenantId(tenantId)
                .triggeredAt(triggeredAt)
                .whatsappLanguageId(whatsappLanguageId)
                .pumpOperatorPhones(phones)
                .build();
        if (!kafkaProducer.publishJson(COMMON_TOPIC, tenantCode, event)) {
            log.warn("[user-events] publish_failed eventType={} tenantCode={} tenantId={} phones={}",
                    eventType, tenantCode, tenantId, phones.size());
        }
    }

    private static List<List<String>> partition(List<String> values, int maxPerBatch) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        if (maxPerBatch <= 0) {
            return List.of(List.copyOf(values));
        }
        if (values.size() <= maxPerBatch) {
            return List.of(List.copyOf(values));
        }
        List<List<String>> out = new ArrayList<>((values.size() + maxPerBatch - 1) / maxPerBatch);
        for (int i = 0; i < values.size(); i += maxPerBatch) {
            int end = Math.min(values.size(), i + maxPerBatch);
            out.add(List.copyOf(values.subList(i, end)));
        }
        return out;
    }
}
