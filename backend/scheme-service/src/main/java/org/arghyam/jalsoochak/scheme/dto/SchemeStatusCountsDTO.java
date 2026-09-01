package org.arghyam.jalsoochak.scheme.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record SchemeStatusCountsDTO(
        long totalSchemes,
        List<CodeCountDTO> workStatusCounts,
        List<CodeCountDTO> operatingStatusCounts
) {
}
