package org.arghyam.jalsoochak.analytics.service;

public interface SchemeStatusSchedulerService {

    SchemeStatusSyncResult syncSchemeStatusBySubmissionRecency(int inactivityThresholdDays);

    record SchemeStatusSyncResult(
            int totalSchemes,
            int activeMarkedCount,
            int inactiveMarkedCount) {
    }
}
