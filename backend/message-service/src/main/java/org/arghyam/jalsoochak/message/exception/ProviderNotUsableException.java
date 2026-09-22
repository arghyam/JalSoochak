package org.arghyam.jalsoochak.message.exception;

/**
 * PER-TENANT-PROVIDERS: a tenant's provider settings exist but cannot be turned into a sender —
 * an unknown provider, a missing or undecryptable credential, or an endpoint the policy refuses.
 *
 * <p>This is a <em>configuration</em> failure, raised while a sender is being built and never once
 * one exists. That distinction is O2-9: a build failure is logged, counted, and answered with the
 * system default, so a misconfigured tenant's OTPs still go out; a failure the provider itself
 * reports after accepting or rejecting a message takes today's retry and failure path instead,
 * because falling back there could deliver the same message twice.
 *
 * <p>Messages name the tenant, the channel and the reason. Never a credential, and never the
 * address a host resolved to — the settings named the host, not the mapping, and echoing it would
 * turn a log line into an internal-network probe.
 */
public class ProviderNotUsableException extends RuntimeException {

    public ProviderNotUsableException(String message) {
        super(message);
    }

    public ProviderNotUsableException(String message, Throwable cause) {
        super(message, cause);
    }
}
