package org.arghyam.jalsoochak.message.channel.provider;

import java.util.Set;

/**
 * Which ledger schemas a pushed delivery report may change, decided by what authenticated it.
 *
 * <p>A credential the platform holds — its own provider account's key, the shared callback token —
 * reaches every schema. A credential that belongs to one tenant's own provider account reaches only
 * that tenant's schema: the account sends that tenant's messages and nobody else's, so a report it
 * signs about another tenant's row is not one it could truthfully make.</p>
 */
public final class ReceiptScope {

    /** Every schema: the request was authenticated by a platform credential, or not checked at all. */
    public static final ReceiptScope ANY = new ReceiptScope(null);

    private final Set<String> schemas;

    private ReceiptScope(Set<String> schemas) {
        this.schemas = schemas;
    }

    /** Only these schemas — the tenants whose own accounts authenticated the request. */
    public static ReceiptScope only(Set<String> schemas) {
        return new ReceiptScope(Set.copyOf(schemas));
    }

    public boolean allows(String schema) {
        return schemas == null || (schema != null && schemas.contains(schema));
    }

    public boolean isRestricted() {
        return schemas != null;
    }

    @Override
    public String toString() {
        return schemas == null ? "ANY" : "only" + schemas;
    }
}
