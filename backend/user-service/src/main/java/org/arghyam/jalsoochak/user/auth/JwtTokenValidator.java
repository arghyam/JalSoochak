package org.arghyam.jalsoochak.user.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class JwtTokenValidator {

    private final String issuerUri;
    private final String keycloakPublicKey;

    private final AtomicReference<JwtDecoder> decoderRef = new AtomicReference<>();

    public JwtTokenValidator(
            @Value("${KEYCLOAK_ISSUER_URI:}") String issuerUri,
            @Value("${KEYCLOAK_PUBLIC_KEY:}") String keycloakPublicKey,
            @Value("${keycloak.public-key:}") String keycloakPublicKeyFallback
    ) {
        this.issuerUri = issuerUri == null ? "" : issuerUri.trim();
        // Prefer env var for consistency across services; fall back to application.yml if set.
        String primary = keycloakPublicKey == null ? "" : keycloakPublicKey.trim();
        String fallback = keycloakPublicKeyFallback == null ? "" : keycloakPublicKeyFallback.trim();
        this.keycloakPublicKey = !primary.isBlank() ? primary : fallback;
    }

    public Jwt decodeAndValidate(String token) throws JwtException {
        return getDecoder().decode(token);
    }

    private JwtDecoder getDecoder() {
        JwtDecoder local = decoderRef.get();
        if (local != null) {
            return local;
        }

        synchronized (this) {
            local = decoderRef.get();
            if (local != null) {
                return local;
            }
            if (keycloakPublicKey.isBlank()) {
                throw new IllegalStateException("KEYCLOAK_PUBLIC_KEY is not configured for user-service");
            }

            RSAPublicKey publicKey = readRsaPublicKey(keycloakPublicKey);
            NimbusJwtDecoder nimbus = NimbusJwtDecoder.withPublicKey(publicKey).build();

            // Always validate exp/nbf; validate issuer if configured.
            OAuth2TokenValidator<Jwt> validator = issuerUri.isBlank()
                    ? new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator())
                    : JwtValidators.createDefaultWithIssuer(issuerUri);
            nimbus.setJwtValidator(validator);

            decoderRef.set(nimbus);
            return nimbus;
        }
    }

    private static RSAPublicKey readRsaPublicKey(String value) {
        String cleaned = value
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] decoded = Base64.getDecoder().decode(cleaned);
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse KEYCLOAK_PUBLIC_KEY as RSA public key", e);
        }
    }
}

