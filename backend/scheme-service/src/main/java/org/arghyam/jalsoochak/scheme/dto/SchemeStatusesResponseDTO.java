package org.arghyam.jalsoochak.scheme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemeStatusesResponseDTO {
    private Integer workStatus;
    private Integer operatingStatus;
}
