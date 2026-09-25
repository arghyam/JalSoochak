#!/bin/sh
set -e

FLYWAY_URL="${FLYWAY_URL:-${DB_URL:-${SPRING_DATASOURCE_URL}}}"
FLYWAY_USER="${FLYWAY_USER:-${FLYWAY_USERNAME:-${SPRING_DATASOURCE_USERNAME}}}"
FLYWAY_PASSWORD="${FLYWAY_PASSWORD:-${SPRING_DATASOURCE_PASSWORD}}"
FLYWAY_LOCATIONS="${FLYWAY_LOCATIONS:-filesystem:/flyway/sql}"
FLYWAY_SCHEMAS="${FLYWAY_SCHEMAS:-common_schema,public}"
SCHEMA_TABLE="${SCHEMA_TABLE:-${FLYWAY_TABLE:-flyway_schema_history}}"

echo "===================================================="
echo "Starting Flyway Database Migration (tenant-service-db)"
echo "Target Schemas: ${FLYWAY_SCHEMAS}"
echo "Locations: ${FLYWAY_LOCATIONS}"
echo "===================================================="

exec flyway \
  -url="${FLYWAY_URL}" \
  -table="${SCHEMA_TABLE}" \
  -user="${FLYWAY_USER}" \
  -password="${FLYWAY_PASSWORD}" \
  -locations="${FLYWAY_LOCATIONS}" \
  -schemas="${FLYWAY_SCHEMAS}" \
  -baselineOnMigrate=true \
  -outOfOrder=true \
  migrate "$@"
