package org.arghyam.jalsoochak.scheme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of re-sending a tenant's schemes to analytics. {@code sentSchemes} counts the messages
 * handed to Kafka; a delivery that fails after that is only logged.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchemeAnalyticsResyncResponseDTO {
    private Integer totalSchemes;
    private Integer sentSchemes;
}
