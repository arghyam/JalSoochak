package org.arghyam.jalsoochak.message.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

import org.arghyam.jalsoochak.message.config.MessagingProviderProperties;
import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.MessagingAllowedHosts;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.arghyam.jalsoochak.message.repository.TenantProviderConfigRepository;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PER-TENANT-PROVIDERS: the second half of the SMTP endpoint check (O2-13), run here immediately
 * before a tenant's sender is built.
 *
 * <p>tenant-service ran the same three checks when the settings were written, and this is not
 * redundant. DNS can change between the write and the send — that is the whole shape of a rebinding
 * attack, and it is also what happens innocently when a state re-points its relay. The allowlist
 * can change too: a super user removing a host from {@code MESSAGING_PROVIDER_ALLOWED_HOSTS} must
 * take effect on the tenants already using it, not only on the next write.
 *
 * <p>Three checks, in order of cost:
 *
 * <ol>
 *   <li>the host is not an IP literal, which would sail past the allowlist's name matching;</li>
 *   <li>the host is covered by {@code MESSAGING_PROVIDER_ALLOWED_HOSTS};</li>
 *   <li>no address the name resolves to is loopback, private or link-local.</li>
 * </ol>
 *
 * <p>A refusal is a {@link ProviderNotUsableException}, which {@code TenantChannelProviders} turns
 * into the system default (O2-9). It fails closed throughout: a name that does not resolve is
 * refused, not accepted on the hope it will resolve safely later.
 *
 * <p>SendGrid and SMSCountry are deliberately not checked: their API hosts are system properties,
 * not tenant settings, so there is no caller-chosen destination to judge.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProviderEndpointPolicy {

    private final TenantProviderConfigRepository configRepository;
    private final HostAddressResolver hostAddressResolver;
    private final MessagingProviderProperties providerProperties;

    /**
     * @throws ProviderNotUsableException if the host is an IP literal, is not allowlisted, does not
     *                                    resolve, or resolves to an address that is not reachable
     *                                    from the public internet
     */
    public void requireUsableSmtpHost(EmailProviderSettings.Smtp smtp) {
        if (smtp == null || smtp.host() == null || smtp.host().isBlank()) {
            throw new ProviderNotUsableException("smtp settings have no host");
        }
        String host = smtp.host().trim().toLowerCase(Locale.ROOT);

        if (isIpLiteral(host)) {
            throw new ProviderNotUsableException("smtp host must be a host name, not an IP address");
        }
        MessagingAllowedHosts allowedHosts = configRepository.findAllowedHosts();
        if (!allowedHosts.allowsSmtpHost(host)) {
            throw new ProviderNotUsableException("smtp host '" + host
                    + "' is no longer in the platform's allowed messaging hosts");
        }
        requireExternallyResolvable(host);
    }

    private void requireExternallyResolvable(String host) {
        if (providerProperties.isAllowInternalHosts()) {
            log.warn("[Providers] Skipping the SMTP address check for host '{}':"
                    + " messaging.provider.allow-internal-hosts is enabled. This must be false in a"
                    + " deployed environment.", host);
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = hostAddressResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new ProviderNotUsableException("smtp host '" + host + "' does not resolve");
        }
        if (addresses == null || addresses.length == 0) {
            throw new ProviderNotUsableException("smtp host '" + host + "' does not resolve");
        }
        for (InetAddress address : addresses) {
            if (SsrfAddressPolicy.isInternalAddress(address)) {
                // Every address is judged, not just the first: a name that resolves to one public
                // and one private address is the standard rebinding shape, and accepting it because
                // one address passed would be the whole hole.
                //
                // The address itself is not echoed. The settings named the host, not the mapping,
                // and repeating what it resolved to turns this log line into an internal-network
                // probe readable by anyone who can see the logs.
                throw new ProviderNotUsableException("smtp host '" + host
                        + "' resolves to an address that is not reachable from the public internet");
            }
        }
    }

    private static boolean isIpLiteral(String host) {
        // An IPv6 literal may arrive bracketed; either form is still a literal.
        String candidate = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        return candidate.contains(":") || candidate.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }
}
