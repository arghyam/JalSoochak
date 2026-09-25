package org.arghyam.jalsoochak.telemetry.repository;

/** A live tenant and the schema provisioned for it. */
public record TelemetryTenantSchema(
        int tenantId,
        String schemaName
) {
}
