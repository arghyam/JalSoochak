package org.arghyam.jalsoochak.analytics.exception;

import org.arghyam.jalsoochak.analytics.enums.RegionHierarchy;

/**
 * Phased rollout: the request names a region that isn't onboarded. Answered as a 404 that doesn't
 * name the region; the message here is for logs only.
 */
public class RegionNotOnboardedException extends RuntimeException {

    public RegionNotOnboardedException(int tenantId, RegionHierarchy hierarchy, int regionId) {
        super(hierarchy + " region " + regionId + " of tenant " + tenantId + " is not onboarded");
    }
}
