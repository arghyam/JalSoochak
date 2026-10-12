package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code SCHEME_READINGS_REASSIGNED} from scheme-service: every reading the tenant stored against
 * {@code fromSchemeId} — a lenient-ingestion placeholder — now belongs to {@code toSchemeId}, the real
 * scheme the state sync matched it to. The tenant DB has already been re-pointed.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemeReadingsReassignedEvent {

    private String eventType;
    private Integer tenantId;
    private Integer fromSchemeId;
    private Integer toSchemeId;
}
