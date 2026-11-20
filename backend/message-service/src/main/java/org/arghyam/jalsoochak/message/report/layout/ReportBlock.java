package org.arghyam.jalsoochak.message.report.layout;

import org.arghyam.jalsoochak.message.report.catalog.ReportRole;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One report's block. The roles listed are exactly the roles that receive the report, in document
 * order; an empty map means nobody does.
 *
 * @param roles unmodifiable; {@code null} only if the stored document omitted it
 */
public record ReportBlock(Map<ReportRole, RoleLayout> roles) {

    public ReportBlock {
        roles = roles == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(roles));
    }
}
