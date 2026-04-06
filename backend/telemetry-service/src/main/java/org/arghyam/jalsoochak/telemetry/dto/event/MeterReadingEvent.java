package org.arghyam.jalsoochak.telemetry.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeterReadingEvent {

    private String eventType;
    private Integer tenantId;
    private Integer schemeId;
    private Integer userId;
    private Integer extractedReading;
    private Integer confirmedReading;
    private Integer confidence;
    private String imageUrl;
    private String readingAt;
    private Integer channel;
    private String readingDate;
    private Integer submissionStatus;
    private Integer readingType;
}
