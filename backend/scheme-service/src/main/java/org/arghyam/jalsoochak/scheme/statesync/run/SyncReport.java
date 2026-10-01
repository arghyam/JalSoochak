package org.arghyam.jalsoochak.scheme.statesync.run;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one run did: named counters, the issues it raised, and the newest upstream scheme
 * {@code updated_at} it saw (the next delta's starting point). Single-threaded by design — one run
 * owns one report.
 */
public class SyncReport {

    private final Map<String, Integer> counts = new TreeMap<>();
    private final List<SyncIssue> issues = new ArrayList<>();
    private LocalDateTime sourceWatermark;

    public void count(String key) {
        add(key, 1);
    }

    public void add(String key, int delta) {
        if (delta != 0) {
            counts.merge(key, delta, Integer::sum);
        }
    }

    public void issue(String entity, String upstreamCode, String category, Map<String, Object> detail) {
        issues.add(new SyncIssue(entity, upstreamCode, category, detail));
        count("issues." + category);
    }

    public void seenSourceUpdatedAt(LocalDateTime updatedAt) {
        if (updatedAt != null && (sourceWatermark == null || updatedAt.isAfter(sourceWatermark))) {
            sourceWatermark = updatedAt;
        }
    }

    public int get(String key) {
        return counts.getOrDefault(key, 0);
    }

    public Map<String, Integer> counts() {
        return Collections.unmodifiableMap(counts);
    }

    public List<SyncIssue> issues() {
        return Collections.unmodifiableList(issues);
    }

    public LocalDateTime sourceWatermark() {
        return sourceWatermark;
    }
}
