package org.arghyam.jalsoochak.scheme.statesync.run;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.kafka.SchemeDimensionEvents;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.SchemeReconciler.Reassignment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the analytics events for what a run changed. Called only after the run's transaction has
 * committed — never for a DRY_RUN — so analytics never sees a row that was rolled back.
 *
 * <p>Schemes go out as {@code SCHEME_DIMENSION_REPLACED} (see {@link SchemeDimensionEvents}), the same
 * event the scheme uploads send.
 *
 * <p>{@code SCHEME_READINGS_REASSIGNED} follows, one per placeholder whose readings moved, after the
 * real scheme's dim rows so analytics never holds facts for a scheme with no dim row. Department
 * events on {@code scheme-service-topic}, user events on {@code user-service-topic}, as elsewhere.
 */
@Component
@Slf4j
public class StateSyncEventPublisher {

    static final String SCHEME_TOPIC = "scheme-service-topic";
    static final String USER_TOPIC = "user-service-topic";

    /** Everything a run wants announced, gathered while it reconciles. */
    public record PendingEvents(List<Integer> schemeIds, List<Map<String, Object>> userEvents,
                                List<Map<String, Object>> departmentEvents, List<Reassignment> reassignments) {

        public static PendingEvents empty() {
            return new PendingEvents(List.of(), List.of(), List.of(), List.of());
        }
    }

    private final KafkaProducer kafkaProducer;
    private final SchemeDimensionEvents schemeDimensionEvents;

    public StateSyncEventPublisher(KafkaProducer kafkaProducer, SchemeDimensionEvents schemeDimensionEvents) {
        this.kafkaProducer = kafkaProducer;
        this.schemeDimensionEvents = schemeDimensionEvents;
    }

    /**
     * What did not reach Kafka. A failed {@code SCHEME_DIMENSION_REPLACED} is repaired by
     * {@code POST /api/v1/scheme/schemes/dimensions/republish}. A failed department or user event is
     * only counted; it goes out again when that node or user next changes. A failed
     * {@code SCHEME_READINGS_REASSIGNED} cannot come back on its own (the placeholder is already
     * retired), so it is returned by itself for the run to record with both scheme ids.
     */
    public record Outcome(int failures, List<Reassignment> failedReassignments) {
    }

    /** Sends everything in order; never throws for a single failed send. */
    public Outcome publish(String schema, int tenantId, PendingEvents events) {
        int failures = 0;
        List<Reassignment> failedReassignments = new ArrayList<>();
        for (Map<String, Object> event : events.departmentEvents()) {
            failures += send(SCHEME_TOPIC, event);
        }
        for (Map<String, Object> event : events.userEvents()) {
            failures += send(USER_TOPIC, event);
        }
        for (Map<String, Object> event : schemeDimensionEvents.build(schema, tenantId, events.schemeIds())) {
            failures += send(SCHEME_TOPIC, event);
        }
        for (Reassignment move : events.reassignments()) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "SCHEME_READINGS_REASSIGNED");
            event.put("tenantId", tenantId);
            event.put("fromSchemeId", move.fromSchemeId());
            event.put("toSchemeId", move.toSchemeId());
            int failed = send(SCHEME_TOPIC, event);
            failures += failed;
            if (failed > 0) {
                failedReassignments.add(move);
            }
        }
        if (failures > 0) {
            log.warn("[state-sync] {} analytics event(s) failed to publish for tenant {}", failures, tenantId);
        }
        return new Outcome(failures, List.copyOf(failedReassignments));
    }

    private int send(String topic, Map<String, Object> event) {
        return kafkaProducer.publishJson(topic, event) ? 0 : 1;
    }
}
