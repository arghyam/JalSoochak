package org.arghyam.jalsoochak.tenant.enums;

import java.util.Arrays;

public enum TenantStatusEnum {

    INACTIVE(0),
    ONBOARDED(1),
    CONFIGURED(2),
    ACTIVE(3),
    SUSPENDED(4),
    DEGRADED(5),
    ARCHIVED(6),
    /**
     * Seeded tenant whose identity exists in {@code tenant_master_table} but which has
     * not yet been onboarded/provisioned via the create-tenant API. Such tenants have no
     * tenant schema and are hidden from all tenant listings and per-tenant iterations.
     */
    REGISTERED(7);

    private final int code;

    TenantStatusEnum(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static TenantStatusEnum fromCode(int code) {
        return Arrays.stream(values())
                .filter(s -> s.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown TenantStatusEnum code: " + code));
    }
}
