package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Sends a tenant's stored ELM and PDU readings over a date range to analytics again, so their water
 * quantities are worked out from the formula and pump data configured now. A reading stored before
 * its tenant's formula or its scheme's pumps were set up has no water quantity, and saving that
 * configuration does not re-send it.
 *
 * <p>ELM and PDU only: their water quantity depends on that configuration, and a BFM reading's does
 * not. A BFM reading published before submission identity (analytics V50) also has a fact row with
 * no source reading id, so republishing it would add a second fact row instead of updating the first.
 *
 * <p>Safe to repeat: analytics updates the one fact row it holds for each reading, and applies an
 * event that carries the version it already has.
 *
 * <p>Each reading is published on the calling thread and waits for Kafka to acknowledge it, rather
 * than being queued on the shared {@code kafkaPublisherExecutor} like a live submission's event. A run
 * can cover thousands of readings, and once that executor's bounded queue is full it rejects live
 * submissions' events too. The run stops at the first reading Kafka does not acknowledge, since every
 * later one would wait out the same timeout, and reports how many were not sent. Readings go oldest
 * first, so the caller can send the same range again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadingBackfillService {

    public static final Set<ReadingChannel> REPUBLISHABLE_CHANNELS =
            Collections.unmodifiableSet(EnumSet.of(ReadingChannel.ELM, ReadingChannel.PDU));

    static final String SCHEME_NOT_FOUND = "Scheme not found for the provided state or centre scheme id";

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final ReadingRepublisher readingRepublisher;

    /**
     * @param republishedCount readings Kafka acknowledged
     * @param withheldCount    readings not sent because they are still quarantined
     * @param notSentCount     readings not sent because Kafka stopped acknowledging them: the first it
     *                         did not acknowledge and every one after it; 0 when the run finished
     */
    public record Outcome(int republishedCount, int withheldCount, int notSentCount) {
    }

    /**
     * @param stateSchemeId  looked up first, as on {@code POST /readings}; blank with a blank
     *                       {@code centreSchemeId} covers every scheme
     * @param centreSchemeId looked up when {@code stateSchemeId} is blank or matches no scheme
     * @param channel        {@code null} for every channel in {@link #REPUBLISHABLE_CHANNELS}
     * @throws ResponseStatusException 404 when a scheme id is given and neither matches a scheme
     */
    public Outcome republish(Integer tenantId,
                             LocalDate fromDate,
                             LocalDate toDate,
                             String stateSchemeId,
                             String centreSchemeId,
                             ReadingChannel channel) {
        Set<ReadingChannel> channels = channel != null ? EnumSet.of(channel) : REPUBLISHABLE_CHANNELS;
        if (!REPUBLISHABLE_CHANNELS.containsAll(channels)) {
            throw new IllegalArgumentException("Only ELM and PDU readings can be republished: " + channels);
        }
        String schemaName = telemetryTenantRepository.findSchemaNameByTenantId(tenantId)
                .filter(schema -> !schema.isBlank())
                .orElseThrow(() -> new IllegalStateException("Tenant schema could not be resolved"));
        Long schemeId = resolveScheme(schemaName, stateSchemeId, centreSchemeId);

        List<Long> readingIds = telemetryTenantRepository.findFlowReadingIdsForRepublish(
                schemaName, fromDate, toDate, schemeId, channels);
        int republished = 0;
        int withheld = 0;
        for (Long readingId : readingIds) {
            ReadingRepublisher.Result result = readingRepublisher.republishAndAwait(schemaName, tenantId, readingId);
            if (result == ReadingRepublisher.Result.NOT_ACKNOWLEDGED) {
                break;
            }
            if (result == ReadingRepublisher.Result.PUBLISHED) {
                republished++;
            } else {
                withheld++;
            }
        }
        int notSent = readingIds.size() - republished - withheld;
        if (notSent > 0) {
            log.warn("reading_backfill_stopped reason=\"not acknowledged\" tenantId={} fromDate={} toDate={} schemeId={} "
                            + "channels={} republished={} withheld={} notSent={}",
                    tenantId, fromDate, toDate, schemeId, channels, republished, withheld, notSent);
        } else {
            log.info("reading_backfill tenantId={} fromDate={} toDate={} schemeId={} channels={} republished={} withheld={}",
                    tenantId, fromDate, toDate, schemeId, channels, republished, withheld);
        }
        return new Outcome(republished, withheld, notSent);
    }

    /** {@code null} when no scheme id was given, meaning every scheme. */
    private Long resolveScheme(String schemaName, String stateSchemeId, String centreSchemeId) {
        boolean hasStateSchemeId = stateSchemeId != null && !stateSchemeId.isBlank();
        boolean hasCentreSchemeId = centreSchemeId != null && !centreSchemeId.isBlank();
        if (!hasStateSchemeId && !hasCentreSchemeId) {
            return null;
        }
        return telemetryTenantRepository.findSchemeIdByStateSchemeId(schemaName, stateSchemeId)
                .or(() -> telemetryTenantRepository.findSchemeIdByCentreSchemeId(schemaName, centreSchemeId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SCHEME_NOT_FOUND));
    }
}
