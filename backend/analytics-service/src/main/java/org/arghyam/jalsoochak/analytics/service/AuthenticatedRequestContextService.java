package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthenticatedRequestContextService {

    private final DimTenantRepository dimTenantRepository;
    private final DimUserRepository dimUserRepository;

    public AnalyticsControllerHelper.AuthenticatedUserRef extractAuthenticatedUserRef(JwtAuthenticationToken authentication) {
        AnalyticsControllerHelper.AuthenticatedUserRef baseRef =
                AnalyticsControllerHelper.extractAuthenticatedUserRef(authentication);

        String tenantStateCode = AnalyticsControllerHelper.getCurrentUserTenantStateCode(authentication);
        Integer tenantId = resolveTenantId(tenantStateCode);

        return new AnalyticsControllerHelper.AuthenticatedUserRef(baseRef.userId(), baseRef.userUuid(), tenantId);
    }

    /** Throws {@link IllegalArgumentException} when no user matches; the handlers answer that with 400. */
    public Integer resolveUserIdByUuid(Integer tenantId, UUID userUuid) {
        return dimUserRepository.findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(tenantId, userUuid)
                .map(u -> u.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("No user found for uuid: " + userUuid));
    }

    private Integer resolveTenantId(String tenantStateCode) {
        if (tenantStateCode == null || tenantStateCode.isBlank()) {
            return 0; // SUPER_USER or tokens without tenant context
        }

        DimTenant tenant = dimTenantRepository.findByStateCode(tenantStateCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No tenant found for tenant_state_code=" + tenantStateCode));
        return tenant.getTenantId();
    }
}

