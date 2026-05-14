package org.arghyam.jalsoochak.scheme.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SchemeActivitySyncScheduler {

    private final SchemeDbRepository schemeDbRepository;

    @Value("${scheme.activity-sync.enabled:true}")
    private boolean enabled;

    @Value("${scheme.activity-sync.inactivity-days:30}")
    private int inactivityDays;

    @Scheduled(
            cron = "${scheme.activity-sync.cron:0 15 2 * * *}",
            zone = "${scheme.activity-sync.zone:UTC}"
    )
    public void syncSchemeActivityStatus() {
        if (!enabled) {
            return;
        }
        List<String> tenantSchemas = schemeDbRepository.findAllActiveTenantSchemas();
        if (tenantSchemas.isEmpty()) {
            log.info("Scheme activity sync skipped: no active tenant schemas found");
            return;
        }

        int totalUpdated = 0;
        int failures = 0;
        for (String schemaName : tenantSchemas) {
            try {
                schemeDbRepository.ensureIsActiveColumnExists(schemaName);
                int updated = schemeDbRepository.syncIsActiveByRecentFlowReadings(schemaName, inactivityDays);
                totalUpdated += updated;
            } catch (Exception ex) {
                failures++;
                log.error("Scheme activity sync failed for schema={}", schemaName, ex);
            }
        }
        log.info(
                "Scheme activity sync completed: tenants={}, updated_rows={}, failures={}, inactivity_days={}",
                tenantSchemas.size(),
                totalUpdated,
                failures,
                inactivityDays
        );
    }
}
