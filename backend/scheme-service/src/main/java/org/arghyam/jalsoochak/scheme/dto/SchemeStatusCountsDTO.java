package org.arghyam.jalsoochak.scheme.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record SchemeStatusCountsDTO(
        long totalSchemes,
        long activeSchemes,
        long inactiveSchemes,
        List<CodeCountDTO> statusCounts,
        List<CodeCountDTO> workStatusCounts,
        List<CodeCountDTO> operatingStatusCounts
) {
}
