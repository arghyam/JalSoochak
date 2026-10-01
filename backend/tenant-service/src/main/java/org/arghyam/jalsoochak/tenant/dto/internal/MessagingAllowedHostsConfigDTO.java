package org.arghyam.jalsoochak.tenant.dto.internal;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.security.HostNames;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MESSAGING-PROVIDER-SETTINGS: the {@code MESSAGING_PROVIDER_ALLOWED_HOSTS} system config value —
 * the hosts a tenant's SMTP settings are allowed to name (O2-13).
 *
 * <p>Only a super user writes this, through the existing {@code PUT /api/v1/system/config}. It is
 * the reason a state admin can set their own SMTP server without being able to point message-service
 * at an arbitrary one: onboarding a new state relay is an explicit platform decision, recorded here.
 *
 * <p>An entry is either an exact host name or a {@code *.suffix} wildcard, which matches any host
 * below the suffix at any depth but <em>not</em> the suffix itself — {@code *.nic.in} covers
 * {@code smtp.nic.in} and {@code mail.up.nic.in}, and an apex host needs its own entry. Bare
 * {@code *} and IP literals are refused: the first would defeat the allowlist, and the second would
 * skip the name-based check that makes the address policy meaningful.
 *
 * <p>An unset or empty list means no tenant may use SMTP. Failing closed is what makes the key a
 * control rather than a formality — the alternative would silently allow every host until the first
 * time someone remembered to populate it.
 *
 * <p>System config values arrive as {@code JsonNode} and are bound with {@code ObjectMapper}, which
 * does not run bean validation, so {@link #validatedSmtpHosts()} is the enforced path — on the
 * write, where a bad pattern is the caller's to fix. {@link #allowsSmtpHost(String)} does not run
 * it: what is already stored has to be matched as it stands, not re-judged.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Host patterns a tenant's provider settings may point at, per provider")
public final class MessagingAllowedHostsConfigDTO implements ConfigValueDTO {

    public static final int MAX_PATTERN_LENGTH = 253;
    public static final int MAX_PATTERNS = 100;

    private static final String WILDCARD_PREFIX = "*.";

    /** Hostname label rules: letters, digits and hyphens, not starting or ending with a hyphen. */
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))*$");

    @NotNull(message = "smtp host list is required")
    @Schema(description = "Exact host names or *.suffix wildcards", example = "[\"smtp.mp.gov.in\", \"*.nic.in\"]")
    private List<String> smtp;

    /** @see UnknownPropertyGuard — an unknown property here is a 400, never a dropped field. */
    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        UnknownPropertyGuard.reject(property, "MESSAGING_PROVIDER_ALLOWED_HOSTS");
    }

    /**
     * Validates and normalises the SMTP patterns to lower case.
     *
     * @throws InvalidConfigValueException if the list is null, too long, or holds a blank, bare-wildcard,
     *                                     over-length or syntactically invalid pattern
     */
    public List<String> validatedSmtpHosts() {
        if (smtp == null) {
            throw new InvalidConfigValueException(
                    "MESSAGING_PROVIDER_ALLOWED_HOSTS must contain an 'smtp' list (use [] to allow none)");
        }
        if (smtp.size() > MAX_PATTERNS) {
            throw new InvalidConfigValueException(
                    "MESSAGING_PROVIDER_ALLOWED_HOSTS.smtp must not exceed " + MAX_PATTERNS + " entries");
        }
        return smtp.stream().map(MessagingAllowedHostsConfigDTO::validatedPattern).toList();
    }

    private static String validatedPattern(String pattern) {
        String normalised = pattern == null ? "" : pattern.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            throw new InvalidConfigValueException(
                    "MESSAGING_PROVIDER_ALLOWED_HOSTS.smtp must not contain a blank entry");
        }
        if (normalised.length() > MAX_PATTERN_LENGTH) {
            throw new InvalidConfigValueException("Host pattern '" + normalised + "' exceeds "
                    + MAX_PATTERN_LENGTH + " characters");
        }
        String host = normalised.startsWith(WILDCARD_PREFIX)
                ? normalised.substring(WILDCARD_PREFIX.length())
                : normalised;
        if (host.isEmpty() || host.contains("*")) {
            throw new InvalidConfigValueException("Host pattern '" + normalised
                    + "' is not a host name or a *.suffix wildcard");
        }
        if (HostNames.isIpLiteral(host)) {
            // Checked here and not left to HOST_PATTERN, which matches all-numeric labels and so
            // stores "10.0.0.5" clean. A literal in the list would be matched by name and never
            // resolved, so the address policy — the check the allowlist exists to make meaningful —
            // would never see it. MessagingProviderSettingsValidator refuses a literal on the
            // settings side; this is the half that stops one being allowlisted in the first place.
            throw new InvalidConfigValueException("Host pattern '" + normalised
                    + "' must be a host name, not an IP address");
        }
        if (!host.contains(".")) {
            // A single-label suffix ("*.in", or "localhost") is either a whole public suffix or an
            // internal name; neither is something a state relay should be reachable as.
            throw new InvalidConfigValueException("Host pattern '" + normalised
                    + "' must name at least two labels");
        }
        if (!HOST_PATTERN.matcher(host).matches()) {
            throw new InvalidConfigValueException("Host pattern '" + normalised + "' is not a valid host name");
        }
        return normalised;
    }

    /**
     * Whether {@code host} is covered by this allowlist. {@code host} is compared case-insensitively
     * against the stored patterns, each normalised as it is compared.
     *
     * <p>Matching does not re-run {@link #validatedSmtpHosts()}. Validation belongs to the write —
     * {@code SystemManagementServiceImpl} runs it before the upsert — and re-running it here made
     * one malformed entry in the stored list throw on <em>every</em> tenant's SMTP settings write,
     * for any host, with an error naming nothing the caller had sent. A row written before that
     * validation existed, or seeded directly, is exactly the case this has to survive.
     *
     * <p>So an unusable entry is skipped rather than fatal, character for character what
     * message-service's {@code MessagingAllowedHosts.allowsSmtpHost} does with the same stored
     * value. The two halves must answer alike: a host one service allows and the other refuses
     * shows up as mail that silently falls back to the system default.
     */
    public boolean allowsSmtpHost(String host) {
        if (host == null || host.isBlank() || smtp == null) {
            return false;
        }
        String candidate = host.trim().toLowerCase(Locale.ROOT);
        for (String raw : smtp) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String pattern = raw.trim().toLowerCase(Locale.ROOT);
            if (pattern.startsWith(WILDCARD_PREFIX)) {
                // endsWith(".suffix") alone would let the apex through on a bare suffix of equal
                // length, so the candidate must be strictly longer than ".suffix".
                String suffix = pattern.substring(1);
                if (candidate.endsWith(suffix) && candidate.length() > suffix.length()) {
                    return true;
                }
            } else if (pattern.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
