package org.arghyam.jalsoochak.tenant.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.arghyam.jalsoochak.tenant.config.properties.MessagingProviderProperties;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.EmailProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.MessagingAllowedHostsConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SmsProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.enums.SystemConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.security.HostAddressResolver;
import org.arghyam.jalsoochak.tenant.security.HostNames;
import org.arghyam.jalsoochak.tenant.security.SsrfAddressPolicy;
import org.arghyam.jalsoochak.tenant.util.TenantConstants;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MESSAGING-PROVIDER-SETTINGS: the rules a provider settings value must satisfy that bean
 * validation on the DTO cannot express — the ones that are cross-field, or that need the system
 * config or DNS.
 *
 * <p>The SMTP checks (O2-13) are the security-relevant half. A state admin legitimately owns their
 * state's SMTP credentials, but the host those credentials are sent to is not theirs to choose
 * freely: an unconstrained host would let a settings write make message-service open a connection
 * to an internal service (SSRF) or hand the tenant's SMTP password to a server the writer controls.
 * Three checks stand between the two, in order of cost:
 *
 * <ol>
 *   <li>the host is covered by {@code MESSAGING_PROVIDER_ALLOWED_HOSTS}, which only a super user
 *       writes — so adding a relay is a platform decision;</li>
 *   <li>the connection is encrypted — STARTTLS, or the implicit-TLS port;</li>
 *   <li>no address the name resolves to is loopback, private or link-local.</li>
 * </ol>
 *
 * <p>Check 3 is repeated by message-service immediately before it connects. Doing it here as well
 * is not redundant: a name that resolves internally is rejected while someone is looking at the
 * error, rather than turning into mail that silently falls back to the system default. Doing it
 * only here would not be enough either — DNS can change between the write and the send.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessagingProviderSettingsValidator {

    /** The one port where TLS is established before the SMTP conversation starts (SMTPS). */
    private static final int IMPLICIT_TLS_PORT = 465;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");
    private static final String OTP_PLACEHOLDER = "otp";
    private static final String EXPIRY_PLACEHOLDER = "expiryMinutes";

    private final TenantCommonRepository tenantCommonRepository;
    private final ObjectMapper objectMapper;
    private final HostAddressResolver hostAddressResolver;
    private final MessagingProviderProperties providerProperties;

    /**
     * @throws InvalidConfigValueException if the settings do not match their provider, or an SMTP
     *                                     host is not allowlisted, not encrypted or resolves internally
     */
    public void validateEmailSettings(EmailProviderConfigDTO settings) {
        switch (settings.getProvider()) {
            case SENDGRID -> {
                requireAbsent(settings.getSmtp(), "smtp", "sendgrid");
                if (settings.getSendgrid() == null) {
                    throw new InvalidConfigValueException("'sendgrid' settings are required when provider is sendgrid");
                }
            }
            case SMTP -> {
                requireAbsent(settings.getSendgrid(), "sendgrid", "smtp");
                if (settings.getSmtp() == null) {
                    throw new InvalidConfigValueException("'smtp' settings are required when provider is smtp");
                }
                validateSmtpEndpoint(settings.getSmtp());
            }
        }
        validateLogoImageUrl(settings.getLogoImageUrl());
    }

    /**
     * @throws InvalidConfigValueException if the settings do not match their provider, or the OTP
     *                                     template's placeholders are wrong
     */
    public void validateSmsSettings(SmsProviderConfigDTO settings) {
        switch (settings.getProvider()) {
            case SMSCOUNTRY -> {
                if (settings.getSmscountry() == null) {
                    throw new InvalidConfigValueException(
                            "'smscountry' settings are required when provider is smscountry");
                }
                validateOtpTemplate(settings.getSmscountry().getOtpTemplate());
            }
        }
    }

    // ── SMTP endpoint (O2-13) ───────────────────────────────────────────────────

    private void validateSmtpEndpoint(EmailProviderConfigDTO.SmtpSettings smtp) {
        String host = smtp.getHost().trim().toLowerCase(Locale.ROOT);

        // An IP literal would sail past the allowlist's name matching and past any later re-check
        // of the name, so it is refused before either runs.
        if (HostNames.isIpLiteral(host)) {
            throw new InvalidConfigValueException(
                    "smtp.host must be a host name, not an IP address");
        }
        if (!loadAllowedHosts().allowsSmtpHost(host)) {
            throw new InvalidConfigValueException("smtp.host '" + host
                    + "' is not in the platform's allowed messaging hosts. A super user must add it to "
                    + SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS + " first.");
        }
        if (!Boolean.TRUE.equals(smtp.getStartTls()) && smtp.getPort() != IMPLICIT_TLS_PORT) {
            throw new InvalidConfigValueException("smtp.startTls must be true on port " + smtp.getPort()
                    + "; only port " + IMPLICIT_TLS_PORT + " may disable it, where TLS is implicit");
        }
        requireExternallyResolvable(host);
    }

    /**
     * Fails closed: a name that does not resolve is refused rather than accepted on the hope that it
     * will resolve safely later.
     */
    private void requireExternallyResolvable(String host) {
        if (providerProperties.isAllowInternalHosts()) {
            log.warn("Skipping the SMTP address check for host '{}': messaging.provider.allow-internal-hosts "
                    + "is enabled. This must be false in a deployed environment.", host);
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = hostAddressResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new InvalidConfigValueException("smtp.host '" + host + "' does not resolve", e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new InvalidConfigValueException("smtp.host '" + host + "' does not resolve");
        }
        for (InetAddress address : addresses) {
            if (SsrfAddressPolicy.isInternalAddress(address)) {
                // The address is not echoed: the caller supplied the name, not the mapping, and
                // repeating what it resolved to turns the endpoint into an internal-network probe.
                throw new InvalidConfigValueException("smtp.host '" + host
                        + "' resolves to an address that is not reachable from the public internet");
            }
        }
    }

    private MessagingAllowedHostsConfigDTO loadAllowedHosts() {
        ConfigDTO stored = tenantCommonRepository.findConfigByTenantAndKey(
                        TenantConstants.SYSTEM_TENANT_ID,
                        SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS.name())
                .orElseThrow(() -> new InvalidConfigValueException(
                        "No SMTP hosts are allowed: a super user must set "
                                + SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS
                                + " before a tenant can use SMTP."));
        try {
            return objectMapper.readValue(stored.getConfigValue(), MessagingAllowedHostsConfigDTO.class);
        } catch (JsonProcessingException e) {
            throw new InvalidConfigValueException("Malformed stored value for "
                    + SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS, e);
        }
    }

    // ── other settings ──────────────────────────────────────────────────────────

    private void validateLogoImageUrl(String logoImageUrl) {
        if (logoImageUrl == null || logoImageUrl.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = new URI(logoImageUrl.trim());
        } catch (URISyntaxException e) {
            throw new InvalidConfigValueException("logoImageUrl is not a valid URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new InvalidConfigValueException("logoImageUrl must be an absolute https URL");
        }
    }

    /**
     * {@code {otp}} exactly once, {@code {expiryMinutes}} at most once, nothing else in braces.
     *
     * <p>A missing {@code {otp}} would send the user a message with no code in it; a repeated one
     * would put the code on the wire twice. An unknown placeholder is a typo that would reach the
     * user verbatim, and would also break the DLT match the operator does on the registered text.
     */
    private void validateOtpTemplate(String otpTemplate) {
        if (otpTemplate == null) {
            return;
        }
        String template = otpTemplate.trim();
        if (template.isEmpty()) {
            throw new InvalidConfigValueException("smscountry.otpTemplate must not be blank");
        }
        int otpCount = 0;
        int expiryCount = 0;
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (OTP_PLACEHOLDER.equals(name)) {
                otpCount++;
            } else if (EXPIRY_PLACEHOLDER.equals(name)) {
                expiryCount++;
            } else {
                throw new InvalidConfigValueException("smscountry.otpTemplate contains unknown placeholder '{"
                        + name + "}'. Supported placeholders: {" + OTP_PLACEHOLDER + "}, {"
                        + EXPIRY_PLACEHOLDER + "}");
            }
        }
        if (otpCount != 1) {
            throw new InvalidConfigValueException("smscountry.otpTemplate must contain {" + OTP_PLACEHOLDER
                    + "} exactly once, found " + otpCount);
        }
        if (expiryCount > 1) {
            throw new InvalidConfigValueException("smscountry.otpTemplate must contain {" + EXPIRY_PLACEHOLDER
                    + "} at most once, found " + expiryCount);
        }
    }

    private static void requireAbsent(Object block, String blockName, String provider) {
        if (block != null) {
            throw new InvalidConfigValueException(
                    "'" + blockName + "' settings must not be present when provider is " + provider);
        }
    }
}
