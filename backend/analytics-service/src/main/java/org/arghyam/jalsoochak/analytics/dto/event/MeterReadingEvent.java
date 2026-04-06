package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
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
