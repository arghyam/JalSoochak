package org.arghyam.jalsoochak.scheme.statesync.run;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.kafka.SchemeDimensionEventPayloads;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Publishes the analytics dimension events for what a run changed. Called only after the run's
 * transaction has committed — never for a DRY_RUN — so analytics never sees a row that was rolled back.
 *
 * <p>Topics follow ownership elsewhere in the platform: scheme and department events on
 * {@code scheme-service-topic}, user and user↔scheme events on {@code user-service-topic}, the topic
 * analytics already reads them from.
 */
@Component
@Slf4j
public class StateSyncEventPublisher {

    static final String SCHEME_TOPIC = "scheme-service-topic";
    static final String USER_TOPIC = "user-service-topic";

    /** Everything a run wants announced, gathered while it reconciles. */
    public record PendingEvents(List<Integer> schemeIds, List<Map<String, Object>> userEvents,
                                List<Map<String, Object>> departmentEvents) {

        public static PendingEvents empty() {
            return new PendingEvents(List.of(), List.of(), List.of());
        }
    }

    private final KafkaProducer kafkaProducer;
    private final SchemeDbRepository schemeDbRepository;

    public StateSyncEventPublisher(KafkaProducer kafkaProducer, SchemeDbRepository schemeDbRepository) {
        this.kafkaProducer = kafkaProducer;
        this.schemeDbRepository = schemeDbRepository;
    }

    /** @return how many events failed to publish (analytics is eventually repaired by the next full run) */
    public int publish(String schema, int tenantId, PendingEvents events) {
        int failures = 0;
        for (Map<String, Object> event : events.departmentEvents()) {
            failures += kafkaProducer.publishJson(SCHEME_TOPIC, event) ? 0 : 1;
        }
        for (Map<String, Object> event : events.userEvents()) {
            failures += kafkaProducer.publishJson(USER_TOPIC, event) ? 0 : 1;
        }
        List<Integer> ids = new ArrayList<>(events.schemeIds());
        for (int from = 0; from < ids.size(); from += 500) {
            List<Integer> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            for (SchemeDbRepository.SchemeAnalyticsRow row : schemeDbRepository.findSchemeAnalyticsRowsBySchemeIds(schema, chunk)) {
                failures += kafkaProducer.publishJson(SCHEME_TOPIC, SchemeDimensionEventPayloads.schemeUpdated(tenantId, row)) ? 0 : 1;
            }
        }
        if (failures > 0) {
            log.warn("[state-sync] {} analytics event(s) failed to publish for tenant {}", failures, tenantId);
        }
        return failures;
    }
}
