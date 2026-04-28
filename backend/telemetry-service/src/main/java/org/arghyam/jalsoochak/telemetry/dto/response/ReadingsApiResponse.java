package org.arghyam.jalsoochak.telemetry.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReadingsApiResponse {
    private boolean success;
    private CreateReadingResponse data;
}
