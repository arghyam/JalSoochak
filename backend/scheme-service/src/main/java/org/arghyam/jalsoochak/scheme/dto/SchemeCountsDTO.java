package org.arghyam.jalsoochak.scheme.dto;

import lombok.Builder;

@Builder
public record SchemeCountsDTO(
        long activeSchemes,
        long inactiveSchemes
) {
}

