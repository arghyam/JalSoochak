package org.arghyam.jalsoochak.scheme.dto;

import lombok.Builder;

@Builder
public record CodeCountDTO(
        String status,
        long count
) {
}
