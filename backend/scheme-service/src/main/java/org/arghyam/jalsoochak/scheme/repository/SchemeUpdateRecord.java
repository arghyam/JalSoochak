package org.arghyam.jalsoochak.scheme.repository;

public record SchemeUpdateRecord(
        Integer id,
        String stateSchemeId,
        String centreSchemeId,
        String schemeName,
        Integer fhtcCount,
        Integer plannedFhtc,
        Integer houseHoldCount,
        Double latitude,
        Double longitude,
        Integer workStatus,
        Integer operatingStatus,
        Integer updatedBy
) {
}
