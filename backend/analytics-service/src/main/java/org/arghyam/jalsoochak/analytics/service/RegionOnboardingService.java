package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.response.OnboardedRegionsResponse;
import org.arghyam.jalsoochak.analytics.enums.RegionHierarchy;
import org.arghyam.jalsoochak.analytics.exception.RegionNotOnboardedException;
import org.arghyam.jalsoochak.analytics.repository.RegionOnboardingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Phased rollout: whether a region is reachable on the dashboards. Below the state, a region is
 * onboarded when it holds at least one scheme in a district flagged {@code is_onboarded}. With
 * {@code analytics.phased-rollout.enabled} off, every region is, and no query runs.
 */
@Service
public class RegionOnboardingService {

    private static final int STATE_LEVEL = 1;

    private final RegionOnboardingRepository repository;
    private final boolean phasedRollout;

    public RegionOnboardingService(
            RegionOnboardingRepository repository,
            @Value("${analytics.phased-rollout.enabled:false}") boolean phasedRollout) {
        this.repository = repository;
        this.phasedRollout = phasedRollout;
    }

    /**
     * The state is always onboarded. So is a region the tenant doesn't have, so that the endpoint
     * rejects it as it does today.
     */
    public boolean isOnboarded(int tenantId, RegionHierarchy hierarchy, int regionId) {
        if (!phasedRollout) {
            return true;
        }
        Optional<Integer> level = repository.findLevel(tenantId, hierarchy, regionId);
        if (level.isEmpty() || level.get() <= STATE_LEVEL) {
            return true;
        }
        if (level.get() > RegionOnboardingRepository.MAX_LEVEL) {
            // No dim_scheme_table column holds a region this deep, so no scheme can turn it on.
            return false;
        }
        return repository.hasSchemeInOnboardedDistrict(tenantId, hierarchy, level.get(), regionId);
    }

    /** @throws RegionNotOnboardedException when {@link #isOnboarded} is false */
    public void requireOnboarded(int tenantId, RegionHierarchy hierarchy, int regionId) {
        if (!isOnboarded(tenantId, hierarchy, regionId)) {
            throw new RegionNotOnboardedException(tenantId, hierarchy, regionId);
        }
    }

    /**
     * The parent's onboarded children, one level down. With the switch off, no query runs and the ids
     * are null: every child is onboarded. Whether the parent itself is onboarded is the guard's call.
     *
     * @throws IllegalArgumentException when the tenant has no such parent
     */
    public OnboardedRegionsResponse onboardedChildren(int tenantId, RegionHierarchy hierarchy, int parentId) {
        OnboardedRegionsResponse.OnboardedRegionsResponseBuilder response = OnboardedRegionsResponse.builder()
                .tenantId(tenantId)
                .parentLgdId(hierarchy == RegionHierarchy.LGD ? parentId : null)
                .parentDepartmentId(hierarchy == RegionHierarchy.DEPARTMENT ? parentId : null)
                .phasedRollout(phasedRollout);
        if (!phasedRollout) {
            return response.build();
        }
        int parentLevel = repository.findLevel(tenantId, hierarchy, parentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        hierarchy + " region " + parentId + " not found for tenant_id: " + tenantId));
        return response
                .onboardedChildIds(repository.findOnboardedChildIds(tenantId, hierarchy, parentLevel, parentId))
                .build();
    }
}
