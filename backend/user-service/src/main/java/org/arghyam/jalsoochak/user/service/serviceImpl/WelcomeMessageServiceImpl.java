package org.arghyam.jalsoochak.user.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.request.WelcomeMessageRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.WelcomeMessageResponseDTO;
import org.arghyam.jalsoochak.user.event.UserEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.arghyam.jalsoochak.user.util.TenantSchemaResolver;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WelcomeMessageServiceImpl implements org.arghyam.jalsoochak.user.service.WelcomeMessageService {

    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final String SUPPORTED_TYPE = "welcome_template";

    private final UserTenantRepository userTenantRepository;
    private final UserCommonRepository userCommonRepository;
    private final UserEventPublisher userEventPublisher;

    @Override
    public WelcomeMessageResponseDTO sendWelcomeMessages(
            String tenantCode,
            WelcomeMessageRequestDTO request,
            Authentication authentication
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        String type = request.getType() == null ? "" : request.getType().trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPE.equals(type)) {
            throw new IllegalArgumentException("type must be '" + SUPPORTED_TYPE + "'");
        }
        List<String> roles = normalizeRoles(request.getRoles());
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }

        String schema = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        String callerTenantCode = SecurityUtils.extractTenantCode(authentication);
        boolean isSuperUser = SecurityUtils.extractRole(authentication)
                .map("SUPER_USER"::equals)
                .orElse(false);

        if (!isSuperUser) {
            if (callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(tenantCode)) {
                throw new ForbiddenAccessException("State admin can only send welcome messages within their own state");
            }
        }

        Integer tenantId = userCommonRepository.findTenantIdByStateCode(tenantCode)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found for code: " + tenantCode));

        Instant after = parseInstantOrDate(request.getOnboardedAfter(), true);
        Instant before = parseInstantOrDate(request.getOnboardedBefore(), false);
        if (after != null && before != null && !before.isAfter(after)) {
            throw new IllegalArgumentException("onboardedBefore must be after onboardedAfter");
        }

        final List<String> batch = new ArrayList<>(DEFAULT_BATCH_SIZE);
        final int[] totalPhones = {0};
        final int[] batches = {0};

        userTenantRepository.streamPhonesByRolesAndOnboardingWindow(
                schema,
                roles,
                after,
                before,
                phone -> {
                    batch.add(phone);
                    if (batch.size() >= DEFAULT_BATCH_SIZE) {
                        userEventPublisher.publishAdminWelcomeMessages(tenantCode, tenantId, batch);
                        totalPhones[0] += batch.size();
                        batches[0]++;
                        batch.clear();
                    }
                }
        );

        if (!batch.isEmpty()) {
            userEventPublisher.publishAdminWelcomeMessages(tenantCode, tenantId, batch);
            totalPhones[0] += batch.size();
            batches[0]++;
            batch.clear();
        }

        String msg = totalPhones[0] == 0
                ? "No matching users found"
                : "Queued welcome messages for " + totalPhones[0] + " users";

        return WelcomeMessageResponseDTO.builder()
                .totalCandidates(totalPhones[0])
                .totalPhones(totalPhones[0])
                .batches(batches[0])
                .batchSize(DEFAULT_BATCH_SIZE)
                .publishedEvents(batches[0])
                .message(msg)
                .build();
    }

    private List<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String[] parts = role.split(",");
            for (String p : parts) {
                String value = p == null ? "" : p.trim();
                if (!value.isEmpty()) {
                    out.add(value.toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(out);
    }

    private Instant parseInstantOrDate(String raw, boolean startOfDay) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // fall back to yyyy-MM-dd
        }
        try {
            LocalDate date = LocalDate.parse(trimmed);
            return startOfDay
                    ? date.atStartOfDay().toInstant(ZoneOffset.UTC)
                    : date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time format: " + trimmed);
        }
    }
}
