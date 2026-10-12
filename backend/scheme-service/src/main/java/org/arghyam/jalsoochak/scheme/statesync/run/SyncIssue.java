package org.arghyam.jalsoochak.scheme.statesync.run;

import java.util.Map;

/**
 * Something a run declined to write, for a human to resolve. {@code detail} must never carry a phone
 * number or other PII — upstream codes and our ids identify the record well enough.
 */
public record SyncIssue(String entity, String upstreamCode, String category, Map<String, Object> detail) {

    public SyncIssue {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
