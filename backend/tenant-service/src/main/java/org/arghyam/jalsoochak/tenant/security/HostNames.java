package org.arghyam.jalsoochak.tenant.security;

/**
 * Host-shape rules shared by everything that decides whether a name a caller supplied may be
 * connected to.
 *
 * <p>MESSAGING-PROVIDER-SETTINGS: {@link #isIpLiteral} has two callers, and they have to agree.
 * {@code MessagingProviderSettingsValidator} refuses a literal as a tenant's {@code smtp.host},
 * and {@code MessagingAllowedHostsConfigDTO} refuses one as an allowlist pattern — the second is
 * what stops a super user writing an entry that would make the first's check the only one, since
 * a literal matched by name skips the DNS resolution that {@link SsrfAddressPolicy} then judges.
 *
 * <p>message-service's {@code ProviderEndpointPolicy} carries the same rule as a private copy, by
 * the same cross-service convention as {@link SsrfAddressPolicy}: there is no shared library module
 * in this repo. Within a service, though, it belongs in one place.
 */
public final class HostNames {

    private HostNames() {
    }

    /**
     * Whether {@code host} is an IP address written out rather than a name.
     *
     * <p>A literal must not reach either the allowlist or the address policy: the allowlist matches
     * on names, so a literal sails past whatever it holds, and the address policy's judgement is
     * about what a name <em>resolves</em> to, which a literal never goes through.
     */
    public static boolean isIpLiteral(String host) {
        if (host == null) {
            return false;
        }
        // An IPv6 literal may arrive bracketed; either form is still a literal.
        String candidate = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        return candidate.contains(":") || candidate.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }
}
