package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.event.NudgeEvent;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.arghyam.jalsoochak.tenant.repository.NudgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Processes nudges for a single tenant. Called by {@link NotificationJobScheduler}
 * on each tenant's individual schedule.
 *
 * <p>Sends one WhatsApp nudge per operator who still has a scheme with nothing recorded today
 * (see {@link NudgeRepository#streamUsersWithNoUploadToday} for what counts).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NudgeSchedulerService {

    private static final String COMMON_TOPIC = "common-topic";

    private final NudgeRepository nudgeRepository;
    private final KafkaProducer kafkaProducer;

    /** Operators active in the chatbot within this many minutes are not nudged (0 = off). */
    @Value("${nudge.quiet-window-minutes:15}")
    private int quietWindowMinutes;

    /**
     * @param runDate the IST day the run is for; operators with nothing recorded on it are nudged
     */
    public void processNudgesForTenant(String schema, int tenantId, LocalDate runDate) {
        int total = nudgeRepository.streamUsersWithNoUploadToday(schema, runDate, quietWindowMinutes, row -> {
            String phone = (String) row.get("phone_number");
            long whatsappId = row.get("whatsapp_connection_id") != null
                    ? ((Number) row.get("whatsapp_connection_id")).longValue() : 0L;
            if ((phone == null || phone.isBlank()) && whatsappId == 0L) return;
            long userId = row.get("user_id") != null ? ((Number) row.get("user_id")).longValue() : 0L;
            NudgeEvent event = NudgeEvent.builder()
                    .eventType("NUDGE")
                    .recipientPhone(phone)
                    .operatorName((String) row.get("name"))
                    .tenantId(tenantId)
                    .languageId(row.get("language_id") != null ? ((Number) row.get("language_id")).intValue() : 0)
                    .userId(userId)
                    .whatsappConnectionId(whatsappId)
                    .tenantSchema(schema)
                    .nudgeDate(runDate.toString())
                    .pendingSchemeCount(row.get("pending_scheme_count") != null
                            ? ((Number) row.get("pending_scheme_count")).intValue() : null)
                    .build();
            kafkaProducer.publishJson(COMMON_TOPIC, tenantId + ":" + userId, event);
            log.debug("[NudgeJob] Published NudgeEvent for userId={} pendingSchemes={}",
                    userId, row.get("pending_scheme_count"));
        });
        log.info("[NudgeJob] schema={} → {} operators nudged for {}", schema, total, runDate);
    }
}
