package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.event.NudgeEvent;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.arghyam.jalsoochak.tenant.repository.NudgeRepository;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Processes nudges for a single tenant. Called by {@link TenantSchedulerManager}
 * on each tenant's individual schedule.
 *
 * <p>Sends a WhatsApp nudge to every operator who has not yet submitted a
 * reading for the current day.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NudgeSchedulerService {

    private static final String COMMON_TOPIC = "common-topic";
    /** reading_date is stored on the IST calendar day, so "today" must be evaluated in IST. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TenantCommonRepository tenantCommonRepository;
    private final NudgeRepository nudgeRepository;
    private final KafkaProducer kafkaProducer;

    public void processNudgesForTenant(String schema, int tenantId) {
        int total = nudgeRepository.streamUsersWithNoUploadToday(schema, LocalDate.now(IST), row -> {
            String phone = (String) row.get("phone_number");
            long whatsappId = row.get("whatsapp_connection_id") != null
                    ? ((Number) row.get("whatsapp_connection_id")).longValue() : 0L;
            if ((phone == null || phone.isBlank()) && whatsappId == 0L) return;
            NudgeEvent event = NudgeEvent.builder()
                    .eventType("NUDGE")
                    .recipientPhone(phone)
                    .operatorName((String) row.get("name"))
                    .schemeId(row.get("scheme_id") != null ? row.get("scheme_id").toString() : "")
                    .tenantId(tenantId)
                    .languageId(row.get("language_id") != null ? ((Number) row.get("language_id")).intValue() : 0)
                    .userId(row.get("user_id") != null ? ((Number) row.get("user_id")).longValue() : 0L)
                    .whatsappConnectionId(whatsappId)
                    .tenantSchema(schema)
                    .build();
            kafkaProducer.publishJson(COMMON_TOPIC, event);
            log.debug("[NudgeJob] Published NudgeEvent for userId={}", row.get("user_id"));
        });
        log.info("[NudgeJob] schema={} → {} users have no upload today", schema, total);
    }
}
