package org.arghyam.jalsoochak.telemetry.repository;

import java.util.regex.Pattern;

/** Guards the tenant schema names this package formats into SQL, where a bind parameter cannot go. */
final class SchemaNames {

    private static final Pattern VALID_SCHEMA_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private SchemaNames() {
    }

    static void validate(String schemaName) {
        if (schemaName == null || !VALID_SCHEMA_NAME.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }
}
