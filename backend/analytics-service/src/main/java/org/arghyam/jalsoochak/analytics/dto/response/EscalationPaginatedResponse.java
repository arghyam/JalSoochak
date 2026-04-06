package org.arghyam.jalsoochak.analytics.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EscalationPaginatedResponse {
    private boolean success;
    private int page;
    private int limit;
    @JsonProperty("total_count")
    private long totalCount;
    private List<EscalationListItemDto> escalations;
}

