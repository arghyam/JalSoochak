package org.arghyam.jalsoochak.telemetry.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.event.UserLanguageUpdateEvent;
import org.arghyam.jalsoochak.telemetry.kafka.KafkaProducer;
import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Hands an operator's language to message-service, which sets it on the operator's WhatsApp contact so
 * the provider's own messages follow it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OperatorLanguageEventPublisher {

    public static final String COMMON_TOPIC = "common-topic";
    public static final String EVENT_UPDATE_USER_LANGUAGE = "UPDATE_USER_LANGUAGE";
    private static final String TENANT_SCHEMA_PREFIX = "tenant_";

    private final KafkaProducer kafkaProducer;
    private final LanguageCatalogRepository languageCatalogRepository;

    /**
     * Publishes {@code UPDATE_USER_LANGUAGE} for one operator, keyed by the tenant code as user-service
     * keys its own. Runs on {@code kafkaPublisherExecutor}, so the chatbot's reply never waits on Kafka.
     * Nothing is published when the schema is not a tenant schema, the operator has no phone, or the
     * language is not in the catalog; a failure is logged, never raised.
     *
     * @param language a language name or alias, as stored in {@code user_language_preference}
     */
    @Async("kafkaPublisherExecutor")
    public void publishLanguageUpdated(String schemaName, Integer tenantId, String phone, String language) {
        String tenantCode = toTenantCode(schemaName);
        if (tenantCode == null) {
            log.warn("[operator-language] Not a tenant schema ({}); WhatsApp contact language not updated",
                    schemaName);
            return;
        }
        if (phone == null || phone.isBlank()) {
            log.warn("[operator-language] Operator has no phone (tenantId={});"
                    + " WhatsApp contact language not updated", tenantId);
            return;
        }
        Optional<Integer> whatsappLanguageId = language == null || language.isBlank()
                ? Optional.empty()
                : languageCatalogRepository.findLanguageIdByAlias(language);
        if (whatsappLanguageId.isEmpty()) {
            log.warn("[operator-language] Language '{}' is not in the language catalog (tenantId={});"
                    + " WhatsApp contact language not updated", language, tenantId);
            return;
        }

        UserLanguageUpdateEvent event = UserLanguageUpdateEvent.builder()
                .eventType(EVENT_UPDATE_USER_LANGUAGE)
                .tenantCode(tenantCode)
                .tenantId(tenantId)
                .triggeredAt(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
                .whatsappLanguageId(whatsappLanguageId.get())
                .pumpOperatorPhones(List.of(phone.trim()))
                .build();
        if (!kafkaProducer.publishJson(COMMON_TOPIC, tenantCode, event)) {
            log.warn("[operator-language] publish_failed tenantCode={} tenantId={} whatsappLanguageId={}",
                    tenantCode, tenantId, whatsappLanguageId.get());
        }
    }

    private static String toTenantCode(String schemaName) {
        if (schemaName == null) {
            return null;
        }
        String schema = schemaName.trim();
        if (!schema.startsWith(TENANT_SCHEMA_PREFIX) || schema.length() == TENANT_SCHEMA_PREFIX.length()) {
            return null;
        }
        return schema.substring(TENANT_SCHEMA_PREFIX.length()).toUpperCase(Locale.ROOT);
    }
}
