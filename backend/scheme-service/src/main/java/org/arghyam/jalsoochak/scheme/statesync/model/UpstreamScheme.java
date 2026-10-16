package org.arghyam.jalsoochak.scheme.statesync.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One scheme as the upstream publishes it. Every field is raw upstream text, normalised later by
 * {@code StateVocabulary}, so an unexpected value reaches the reconciler and is reported there
 * rather than being dropped while parsing.
 *
 * @param officers every person attached to the scheme, across all roles (section officer, SDO,
 *                 executive engineer, pump operator); de-duplicated by code
 * @param updatedAt upstream {@code updated_at}, in the upstream's (undocumented) timezone
 */
public record UpstreamScheme(
        String code,
        String centreSchemeId,
        String stateSchemeId,
        String name,
        String workStatus,
        String operatingStatus,
        Integer plannedFhtc,
        Integer achievedFhtc,
        String latitude,
        String longitude,
        List<String> subdivisionCodes,
        List<String> villageCodes,
        List<UpstreamPerson> officers,
        LocalDateTime updatedAt
) {
}
