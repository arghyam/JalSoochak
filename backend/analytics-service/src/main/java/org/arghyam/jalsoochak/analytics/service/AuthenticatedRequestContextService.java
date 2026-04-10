package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthenticatedRequestContextService {

    private final DimTenantRepository dimTenantRepository;

    public AnalyticsControllerHelper.AuthenticatedUserRef extractAuthenticatedUserRef(JwtAuthenticationToken authentication) {
        AnalyticsControllerHelper.AuthenticatedUserRef baseRef =
                AnalyticsControllerHelper.extractAuthenticatedUserRef(authentication);

        String tenantStateCode = AnalyticsControllerHelper.getCurrentUserTenantStateCode(authentication);
        Integer tenantId = resolveTenantId(tenantStateCode);

        return new AnalyticsControllerHelper.AuthenticatedUserRef(baseRef.userId(), baseRef.userUuid(), tenantId);
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

