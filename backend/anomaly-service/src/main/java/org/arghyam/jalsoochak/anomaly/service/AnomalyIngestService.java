package org.arghyam.jalsoochak.anomaly.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.anomaly.dto.event.AnomalyEvent;
import org.arghyam.jalsoochak.anomaly.entity.Anomaly;
import org.arghyam.jalsoochak.anomaly.repository.AnomalyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnomalyIngestService {

    private final AnomalyRepository anomalyRepository;

    public void ingest(AnomalyEvent event) {
        if (event == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        Anomaly anomaly = Anomaly.builder()
                .uuid(event.getUuid() != null && !event.getUuid().isBlank()
                        ? event.getUuid()
                        : UUID.randomUUID().toString())
                .type(event.getType())
                .userId(event.getUserId())
                .schemeId(event.getSchemeId())
                .tenantId(event.getTenantId())
                .aiReading(event.getAiReading())
                .aiConfidencePercentage(event.getAiConfidencePercentage())
                .overriddenReading(event.getOverriddenReading())
                .retries(event.getRetries())
                .previousReading(event.getPreviousReading())
                .previousReadingDate(event.getPreviousReadingDate())
                .consecutiveDaysMissed(event.getConsecutiveDaysMissed())
                .reason(event.getReason())
                .status(event.getStatus())
                .correlationId(event.getCorrelationId())
                // ANOMALY-SUBMISSION-LINK: carried through verbatim; never derived.
                .submissionCorrelationId(event.getSubmissionCorrelationId())
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            anomalyRepository.save(anomaly);
        } catch (DataIntegrityViolationException e) {
            log.debug("Skipping duplicate anomaly for uuid={}", anomaly.getUuid());
        }
    }
}
