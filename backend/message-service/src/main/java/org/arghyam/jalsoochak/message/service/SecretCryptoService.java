package org.arghyam.jalsoochak.message.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.arghyam.jalsoochak.message.config.MessagingSecretProperties;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.arghyam.jalsoochak.message.exception.SecretCryptoException;
import org.arghyam.jalsoochak.message.exception.SecretStoreUnavailableException;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * PER-TENANT-PROVIDERS: envelope encryption for tenant messaging credentials.
 *
 * <p><b>This is a copy of tenant-service's {@code service/SecretCryptoService}, kept identical
 * apart from the package, the imports and this paragraph.</b> There is no shared library module in
 * this repo — the five copies of {@code PiiEncryptionService} are the standing precedent. The two
 * halves are the write side and the read side of one store, so a divergence in the AAD format, the
 * blob layout or the key handling would mean tenant-service writing credentials this service can
 * never decrypt, and every configured tenant silently falling back to the system default. Change
 * both together, and change neither once a tenant secret exists in production without migrating
 * every row. Only the wrap half is unused here: this service reads.
 *
 * <p>Two levels, both AES-256-GCM with a fresh 12-byte {@link SecureRandom} IV and a
 * 128-bit tag, stored as {@code base64(iv || ciphertext+tag)}:
 *
 * <ol>
 *   <li>A per-tenant data key (DEK) is wrapped by a master key (KEK) held only in the
 *       environment. AAD: {@code "<tenantId>|<keyVersion>|<masterKeyId>"}.</li>
 *   <li>Each secret value is encrypted under its tenant's DEK. AAD:
 *       {@code "<tenantId>|<channel>|<secretName>|<keyVersion>"}.</li>
 * </ol>
 *
 * <p>The AAD is what makes a stolen ciphertext useless anywhere but its own row. Moving
 * a wrapped key or a secret to another tenant, channel, secret name or key version
 * changes the AAD, so GCM authentication fails and nothing decrypts. Without it, one
 * tenant's row copied over another's would decrypt cleanly to the wrong credential.
 *
 * <p>Deliberately modelled on {@link PiiEncryptionService} rather than extending it, and
 * on plain JCE rather than a crypto library: the scheme is two AES-GCM operations, a
 * fresh random 96-bit nonce per encryption is safe far beyond the handful of writes a
 * tenant's credentials ever see, and rotation is kept in queryable SQL columns
 * ({@code key_version}, {@code master_key_id}) instead of an opaque keyset blob.
 *
 * <p>Three things this has that {@code PiiEncryptionService} does not:
 * <b>AAD</b>; <b>key versions</b> at both levels; and <b>no plaintext fallback</b> —
 * {@code safeDecrypt}'s legacy-row behaviour would be a security hole here, so every
 * failure raises {@link SecretCryptoException}.
 *
 * <p>Callers own the DEK byte arrays this class hands back and must {@link #zeroise}
 * them once done, ideally in a {@code finally} block. The master keys are zeroised by a
 * shutdown hook, as {@code PiiEncryptionService} does.
 */
@Slf4j
@Service
public class SecretCryptoService {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** Key id to raw 32-byte key. Every id can unwrap; only the active one wraps. */
    private final Map<String, byte[]> masterKeys;

    private final String activeMasterKeyId;
    private final SecureRandom rng = new SecureRandom();

    public SecretCryptoService(MessagingSecretProperties properties) {
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        Map<String, String> configured = properties.getMasterKeys() == null
                ? Map.of()
                : properties.getMasterKeys();

        for (Map.Entry<String, String> entry : configured.entrySet()) {
            String keyId = entry.getKey();
            String encoded = entry.getValue();
            // A yml placeholder with no environment variable behind it binds to "" — that is
            // "not configured", not "misconfigured", so it is skipped rather than rejected.
            if (encoded == null || encoded.isBlank()) {
                continue;
            }
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(encoded.trim());
            } catch (IllegalArgumentException e) {
                // The cause is not attached: its message quotes the offending input.
                throw new IllegalStateException(
                        "messaging.secret.master-keys." + keyId + " is not valid base64");
            }
            if (keyBytes.length != KEY_LENGTH_BYTES) {
                Arrays.fill(keyBytes, (byte) 0);
                throw new IllegalStateException("messaging.secret.master-keys." + keyId
                        + " must decode to exactly " + KEY_LENGTH_BYTES + " bytes (256 bits)");
            }
            decoded.put(keyId, keyBytes);
        }

        String active = properties.getActiveMasterKeyId() == null
                ? null
                : properties.getActiveMasterKeyId().trim();

        if (decoded.isEmpty()) {
            if (active != null && !active.isBlank()) {
                throw new IllegalStateException("messaging.secret.active-master-key-id is '" + active
                        + "' but no master key is configured");
            }
            this.masterKeys = Map.of();
            this.activeMasterKeyId = null;
            log.info("Tenant messaging secret store is disabled: no messaging.secret.master-keys configured. "
                    + "Secret endpoints will answer 503.");
            return;
        }

        if (active == null || active.isBlank()) {
            throw new IllegalStateException("messaging.secret.active-master-key-id must be set when "
                    + "master keys are configured. Configured ids: " + decoded.keySet());
        }
        if (!decoded.containsKey(active)) {
            throw new IllegalStateException("messaging.secret.active-master-key-id '" + active
                    + "' is not one of the configured ids " + decoded.keySet());
        }

        this.masterKeys = decoded;
        this.activeMasterKeyId = active;
        log.info("Tenant messaging secret store enabled [activeMasterKeyId={}, readableKeyIds={}]",
                active, decoded.keySet());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> masterKeys.values().forEach(SecretCryptoService::zeroise)));
    }

    /** {@code false} when no master key is configured, i.e. the store cannot be used at all. */
    public boolean isConfigured() {
        return activeMasterKeyId != null;
    }

    /** The key id new wraps use. */
    public String getActiveMasterKeyId() {
        requireConfigured();
        return activeMasterKeyId;
    }

    /** Every master key id that can unwrap, for reporting rotation progress. */
    public Set<String> getReadableMasterKeyIds() {
        return masterKeys.keySet();
    }

    /**
     * Raises {@link SecretStoreUnavailableException} ({@code 503}) when no master key is
     * configured. Call before any work that would otherwise fail halfway.
     */
    public void requireConfigured() {
        if (activeMasterKeyId == null) {
            throw new SecretStoreUnavailableException(
                    "Tenant messaging secret storage is not available: no master key is configured "
                            + "for this deployment.");
        }
    }

    /** A new 32-byte data key. The caller must {@link #zeroise} it. */
    public byte[] generateDataKey() {
        requireConfigured();
        byte[] dataKey = new byte[KEY_LENGTH_BYTES];
        rng.nextBytes(dataKey);
        return dataKey;
    }

    /** Wraps {@code dataKey} under {@code masterKeyId}, bound to its tenant and version. */
    public String wrapDataKey(byte[] dataKey, Integer tenantId, int keyVersion, String masterKeyId) {
        byte[] masterKey = masterKey(masterKeyId);
        if (dataKey == null || dataKey.length != KEY_LENGTH_BYTES) {
            throw new SecretCryptoException("Data key for tenant " + tenantId + " key version " + keyVersion
                    + " must be exactly " + KEY_LENGTH_BYTES + " bytes");
        }
        return encrypt(dataKey, masterKey, dataKeyAad(tenantId, keyVersion, masterKeyId),
                () -> "wrap data key for tenant " + tenantId + " key version " + keyVersion);
    }

    /**
     * Unwraps a data key. The caller must {@link #zeroise} the result.
     *
     * @throws SecretCryptoException if {@code masterKeyId} is not configured, or the wrapped
     *                               key does not authenticate against this exact tenant and version
     */
    public byte[] unwrapDataKey(String wrappedKey, Integer tenantId, int keyVersion, String masterKeyId) {
        byte[] masterKey = masterKey(masterKeyId);
        return decrypt(wrappedKey, masterKey, dataKeyAad(tenantId, keyVersion, masterKeyId),
                () -> "unwrap data key for tenant " + tenantId + " key version " + keyVersion
                        + " (master key id " + masterKeyId + ")");
    }

    /** Encrypts a secret value under {@code dataKey}, bound to the row it will be stored in. */
    public String encryptSecret(String value, byte[] dataKey, Integer tenantId,
            MessagingChannel channel, String secretName, int keyVersion) {
        requireConfigured();
        byte[] plaintext = value.getBytes(UTF_8);
        try {
            return encrypt(plaintext, dataKey, secretAad(tenantId, channel, secretName, keyVersion),
                    () -> "encrypt secret " + location(tenantId, channel, secretName, keyVersion));
        } finally {
            zeroise(plaintext);
        }
    }

    /**
     * Decrypts a secret value.
     *
     * @throws SecretCryptoException on any failure. There is no fallback: a value that does
     *                               not authenticate is never returned in any form.
     */
    public String decryptSecret(String ciphertext, byte[] dataKey, Integer tenantId,
            MessagingChannel channel, String secretName, int keyVersion) {
        requireConfigured();
        byte[] plaintext = decrypt(ciphertext, dataKey, secretAad(tenantId, channel, secretName, keyVersion),
                () -> "decrypt secret " + location(tenantId, channel, secretName, keyVersion));
        try {
            return new String(plaintext, UTF_8);
        } finally {
            zeroise(plaintext);
        }
    }

    /** Overwrites key or plaintext material in place. Null-safe, so it is usable in a {@code finally}. */
    public static void zeroise(byte[] material) {
        if (material != null) {
            Arrays.fill(material, (byte) 0);
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private byte[] masterKey(String masterKeyId) {
        requireConfigured();
        byte[] key = masterKeys.get(masterKeyId);
        if (key == null) {
            // Names the id, never the key. A rewrap relies on this to skip rows whose
            // master key has already been removed from the environment.
            throw new SecretCryptoException("Master key id '" + masterKeyId
                    + "' is not configured for this deployment. Readable ids: " + masterKeys.keySet());
        }
        return key;
    }

    private String encrypt(byte[] plaintext, byte[] key, String aad, java.util.function.Supplier<String> what) {
        try {
            // A NEW nonce on every encryption, never derived, never reused: GCM's one
            // catastrophic failure mode is a repeated (key, nonce) pair.
            byte[] iv = new byte[IV_LENGTH_BYTES];
            rng.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad.getBytes(UTF_8));
            byte[] ciphertextAndTag = cipher.doFinal(plaintext);

            byte[] output = new byte[IV_LENGTH_BYTES + ciphertextAndTag.length];
            System.arraycopy(iv, 0, output, 0, IV_LENGTH_BYTES);
            System.arraycopy(ciphertextAndTag, 0, output, IV_LENGTH_BYTES, ciphertextAndTag.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (GeneralSecurityException e) {
            // The cause carries no plaintext, but it is dropped anyway so nothing about the
            // value can reach a log through a stack trace.
            throw new SecretCryptoException("Failed to " + what.get() + ": " + e.getClass().getSimpleName());
        }
    }

    private byte[] decrypt(String encoded, byte[] key, String aad, java.util.function.Supplier<String> what) {
        if (encoded == null || encoded.isBlank()) {
            throw new SecretCryptoException("Failed to " + what.get() + ": stored ciphertext is absent");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new SecretCryptoException("Failed to " + what.get() + ": stored value is not valid base64");
        }
        if (decoded.length < IV_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            throw new SecretCryptoException("Failed to " + what.get() + ": stored value is "
                    + decoded.length + " bytes, below the " + (IV_LENGTH_BYTES + TAG_LENGTH_BYTES)
                    + "-byte minimum for AES-GCM");
        }
        try {
            byte[] iv = Arrays.copyOfRange(decoded, 0, IV_LENGTH_BYTES);
            byte[] ciphertextAndTag = Arrays.copyOfRange(decoded, IV_LENGTH_BYTES, decoded.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad.getBytes(UTF_8));
            return cipher.doFinal(ciphertextAndTag);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException lands here: a wrong key, a tampered ciphertext, or a row the
            // AAD says this ciphertext does not belong to. All three are hard failures.
            throw new SecretCryptoException("Failed to " + what.get() + ": " + e.getClass().getSimpleName());
        }
    }

    private static String dataKeyAad(Integer tenantId, int keyVersion, String masterKeyId) {
        return tenantId + "|" + keyVersion + "|" + masterKeyId;
    }

    private static String secretAad(Integer tenantId, MessagingChannel channel, String secretName, int keyVersion) {
        return tenantId + "|" + channel.name() + "|" + secretName + "|" + keyVersion;
    }

    private static String location(Integer tenantId, MessagingChannel channel, String secretName, int keyVersion) {
        return "[tenantId=" + tenantId + ", channel=" + channel + ", secretName=" + secretName
                + ", keyVersion=" + keyVersion + "]";
    }

    /** Key ids only — never key material, since a bean's {@code toString()} reaches logs and actuator output. */
    @Override
    public String toString() {
        return "SecretCryptoService(activeMasterKeyId=" + activeMasterKeyId
                + ", readableMasterKeyIds=" + masterKeys.keySet() + ")";
    }
}
