package org.arghyam.jalsoochak.telemetry.service;

/**
 * The officer behind a staff request, as their verified Keycloak token states it.
 *
 * @param tenantStateCode the token's {@code tenant_state_code} claim
 * @param userUuid        the token's subject, which user-service stores as {@code user_table.uuid}
 */
public record StaffCaller(String tenantStateCode, String userUuid) {
}
