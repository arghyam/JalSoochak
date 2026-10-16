package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code SCHEME_RETIRED} from scheme-service: the state sync soft-deleted {@code schemeId} because the
 * upstream's complete scheme list stopped listing it (it became a child or test scheme there).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemeRetiredEvent {

    private String eventType;
    private Integer tenantId;
    private Integer schemeId;
}
