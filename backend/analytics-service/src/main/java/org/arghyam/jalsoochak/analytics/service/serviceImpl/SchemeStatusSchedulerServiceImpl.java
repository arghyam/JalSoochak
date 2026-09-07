package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.enums.SchemeStatus;
import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;
import org.arghyam.jalsoochak.analytics.repository.SchemeStatusMaintenanceRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeStatusSchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SchemeStatusSchedulerServiceImpl implements SchemeStatusSchedulerService {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final SchemeStatusMaintenanceRepository schemeStatusMaintenanceRepository;

    @Override
    @Transactional
    public SchemeStatusSyncResult syncSchemeStatusBySubmissionRecency(int inactivityThresholdDays) {
        int sanitizedThresholdDays = Math.max(0, inactivityThresholdDays);
        LocalDate today = LocalDate.now(IST_ZONE);
        LocalDate activeCutoffDate = today.minusDays(sanitizedThresholdDays);

        List<SchemeStatusMaintenanceRepository.SchemeLastSubmissionProjection> schemeLastSubmissionRows =
                schemeStatusMaintenanceRepository.findLastSubmittedDateByScheme(SubmissionStatus.SUBMITTED.getCode());

        List<Integer> activeSchemeIds = new ArrayList<>();
        List<Integer> inactiveSchemeIds = new ArrayList<>();

        for (SchemeStatusMaintenanceRepository.SchemeLastSubmissionProjection row : schemeLastSubmissionRows) {
            Integer schemeId = row.getSchemeId();
            LocalDate lastSubmittedDate = row.getLastSubmittedDate();

            if (lastSubmittedDate != null && !lastSubmittedDate.isBefore(activeCutoffDate)) {
                activeSchemeIds.add(schemeId);
            } else {
                inactiveSchemeIds.add(schemeId);
            }
        }

        if (!activeSchemeIds.isEmpty()) {
            schemeStatusMaintenanceRepository.updateSchemeStatusBySchemeIds(
                    SchemeStatus.ACTIVE.getCode(), activeSchemeIds);
        }
        if (!inactiveSchemeIds.isEmpty()) {
            schemeStatusMaintenanceRepository.updateSchemeStatusBySchemeIds(
                    SchemeStatus.INACTIVE.getCode(), inactiveSchemeIds);
        }

        return new SchemeStatusSyncResult(
                schemeLastSubmissionRows.size(),
                activeSchemeIds.size(),
                inactiveSchemeIds.size());
    }
}
