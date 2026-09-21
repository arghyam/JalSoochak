package org.arghyam.jalsoochak.message.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.arghyam.jalsoochak.message.enums.MessagingChannel;

/**
 * PER-TENANT-PROVIDERS: the decrypted credentials for one tenant's one channel, held only long
 * enough to build that tenant's sender.
 *
 * <p>The values are {@link String}s. S-3 asks for resolved values to be zeroised on eviction, and
 * they are not, because they cannot be: every consumer of these — a SendGrid {@code Authorization}
 * header, {@code JavaMailSenderImpl.setPassword}, SMSCountry's basic-auth pair — takes a
 * {@code String}, so a {@code char[]} here would be converted at the first use site and leave the
 * identical copy on the heap with the array zeroised for show. The zeroisation that <em>is</em>
 * real is on the key material: {@code SecretCryptoService} wipes each unwrapped data key and each
 * decrypted plaintext buffer, and its master keys at shutdown. What this class does instead is keep
 * the values out of every accidental path — {@link #toString()}, logs and exception messages —
 * which is the exposure that actually happens.
 *
 * <p>Instances are immutable and are never stored beyond the sender they configure.
 */
public final class TenantSecrets {

    private static final TenantSecrets EMPTY_EMAIL = new TenantSecrets(MessagingChannel.EMAIL, Map.of());
    private static final TenantSecrets EMPTY_SMS = new TenantSecrets(MessagingChannel.SMS, Map.of());

    private final MessagingChannel channel;
    private final Map<String, String> values;

    private TenantSecrets(MessagingChannel channel, Map<String, String> values) {
        this.channel = channel;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static TenantSecrets of(MessagingChannel channel, Map<String, String> values) {
        return new TenantSecrets(channel, values);
    }

    /** No credential resolved for this channel. */
    public static TenantSecrets none(MessagingChannel channel) {
        return channel == MessagingChannel.EMAIL ? EMPTY_EMAIL : EMPTY_SMS;
    }

    public MessagingChannel channel() {
        return channel;
    }

    /** The value stored under {@code name}, or {@code null} when it is not present. */
    public String get(String name) {
        return values.get(name);
    }

    /** The names present, for reporting what is missing. Never the values. */
    public Set<String> names() {
        return values.keySet();
    }

    public boolean hasAll(Set<String> required) {
        for (String name : required) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** Names only. A credential must never reach a log line, an exception message or a heap dump label. */
    @Override
    public String toString() {
        return "TenantSecrets(channel=" + channel + ", names=" + values.keySet() + ")";
    }
}
