package org.arghyam.jalsoochak.telemetry.security;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Refuses to hand the connection pool an internal address for a caller-supplied host.
 *
 * <p>Checking here rather than only at the URL is what makes the guard hold: resolution happens
 * immediately before the socket is opened, so a name that answered publicly during validation and
 * privately a moment later (DNS rebinding) is still caught, and every redirect hop goes through the
 * same gate without the redirect handler having to know about it.
 */
@Slf4j
public class SsrfGuardDnsResolver implements DnsResolver {

    private final DnsResolver delegate;
    private final SsrfAddressPolicy addressPolicy;

    public SsrfGuardDnsResolver(DnsResolver delegate, SsrfAddressPolicy addressPolicy) {
        this.delegate = delegate;
        this.addressPolicy = addressPolicy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] resolved = delegate.resolve(host);
        if (resolved == null || resolved.length == 0) {
            throw new UnknownHostException("No address for media host");
        }
        for (InetAddress address : resolved) {
            if (!addressPolicy.isAllowed(address)) {
                log.warn("media_fetch_blocked reason=\"non-public address\" host={}", sanitizeForLog(host));
                // UnknownHostException is the failure the pool expects here, and it carries no
                // detail an attacker could use as an internal-network oracle.
                throw new UnknownHostException("Media host resolves to a non-public address");
            }
        }
        return resolved;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return delegate.resolveCanonicalHostname(host);
    }

    private static String sanitizeForLog(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        String sanitized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return sanitized.length() <= 100 ? sanitized : sanitized.substring(0, 100) + "...";
    }
}
