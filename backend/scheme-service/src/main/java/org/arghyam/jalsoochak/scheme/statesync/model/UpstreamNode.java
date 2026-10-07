package org.arghyam.jalsoochak.scheme.statesync.model;

/**
 * One node of either upstream hierarchy (departmental: zone → circle → division → sub-division,
 * or LGD: district → block → panchayat → village), with its immediate parent's code.
 *
 * @param parentCode {@code null} for a root level (zone, district)
 */
public record UpstreamNode(String code, String name, String parentCode) {
}
