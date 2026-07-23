package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.dto.response.HourlySubmissionActivityResponse;
import org.arghyam.jalsoochak.analytics.repository.HourlySubmissionActivityRepository;
import org.arghyam.jalsoochak.analytics.service.SubmissionActivityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionActivityServiceImpl implements SubmissionActivityService {

    private static final String HOURLY_ACTIVITY_CACHE_PREFIX = ":submission_activity:hourly";

    /** Cache TTL in hours; 1 so today's hourly counts refresh each hour (matches the other dashboards). */
    @Value("${analytics.cache.ttl-hours:1}")
    private long cacheTtlHours;

    private final HourlySubmissionActivityRepository hourlySubmissionActivityRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public HourlySubmissionActivityResponse getHourlySubmissionActivity(
            Integer tenantId, Integer lgdId, Integer departmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDateRange(startDate, endDate);
        if (lgdId != null && departmentId != null) {
            throw new IllegalArgumentException("Provide either lgd_id or department_id, not both");
        }
        if (lgdId != null && lgdId <= 0) {
            throw new IllegalArgumentException("lgd_id must be a positive integer");
        }
        if (departmentId != null && departmentId <= 0) {
            throw new IllegalArgumentException("department_id must be a positive integer");
        }

        String cacheKey = HOURLY_ACTIVITY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + lgdId
                + ":dept:" + departmentId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        HourlySubmissionActivityResponse cached = readFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<HourlySubmissionActivityRepository.HourlyActivityRow> rows;
        if (lgdId != null) {
            rows = hourlySubmissionActivityRepository.getRegionHourly(tenantId, "LGD", lgdId, startDate, endDate);
        } else if (departmentId != null) {
            rows = hourlySubmissionActivityRepository.getRegionHourly(tenantId, "DEPT", departmentId, startDate, endDate);
        } else {
            rows = hourlySubmissionActivityRepository.getTenantHourly(tenantId, startDate, endDate);
        }

        List<HourlySubmissionActivityResponse.HourlyBucket> buckets = rows.stream()
                .map(r -> HourlySubmissionActivityResponse.HourlyBucket.builder()
                        .hourStart(r.hourStart())
                        .submissionCount(r.submissionCount())
                        .distinctSchemeCount(r.distinctSchemeCount())
                        .build())
                .toList();

        HourlySubmissionActivityResponse response = HourlySubmissionActivityResponse.builder()
                .tenantId(tenantId)
                .lgdId(lgdId)
                .departmentId(departmentId)
                .startDate(startDate)
                .endDate(endDate)
                .hourlyActivity(buckets)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    private void validateTenantInput(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant_id must be a positive integer");
        }
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("start_date and end_date are required");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("end_date must be on or after start_date");
        }
    }

    private HourlySubmissionActivityResponse readFromCache(String cacheKey) {
        try {
            String payload = redisTemplate.opsForValue().get(cacheKey);
            if (payload == null || payload.isBlank()) {
                return null;
            }
            return objectMapper.readValue(payload, HourlySubmissionActivityResponse.class);
        } catch (Exception e) {
            log.warn("Failed to read hourly submission activity cache [{}]: {}", cacheKey, e.getMessage());
            return null;
        }
    }

    private void writeToCache(String cacheKey, Object response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(cacheKey, payload, Duration.ofHours(cacheTtlHours));
        } catch (Exception e) {
            log.warn("Failed to write hourly submission activity cache [{}]: {}", cacheKey, e.getMessage());
        }
    }
}
