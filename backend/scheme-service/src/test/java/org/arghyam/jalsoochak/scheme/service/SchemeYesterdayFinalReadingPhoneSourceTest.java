package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.config.TenantContext;
import org.arghyam.jalsoochak.scheme.dto.SchemeYesterdayFinalReadingDTO;
import org.arghyam.jalsoochak.scheme.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The operator's phone number must come from the tenant database, never from the token.
 *
 * <p>This method used to prefer a {@code phone_number}/{@code phoneNumber}/{@code phone}/
 * {@code mobile} claim and fall back to the encrypted column only when none was present. Reading
 * the claim was what made the phone number worth putting in the token in the first place, and a JWT
 * payload is base64, not encrypted — anyone holding the token can read it. The DB column is
 * encrypted at rest and decrypted per request, so the fallback was always the better source; now it
 * is the only one.
 *
 * <p>The two halves of the fix are order-independent: removing the Keycloak mapper before this
 * lands leaves the code on its existing fallback, and landing this before the mapper is removed
 * simply stops reading a claim that is still present.
 */
@ExtendWith(MockitoExtension.class)
class SchemeYesterdayFinalReadingPhoneSourceTest {

    private static final String SCHEMA = "tenant_ka";
    private static final String TENANT_CODE = "ka";

    /** Not a real number. The stored value is what the caller must be shown. */
    private static final String STORED_PHONE = "919999900001";
    /** Also not real, and deliberately different, so a claim leak is visible in the assertion. */
    private static final String CLAIM_PHONE = "918888800002";

    @Mock
    SchemeDbRepository schemeDbRepository;

    @Mock
    SchemeUploadChunkProcessor chunkProcessor;

    @Mock
    KafkaProducer kafkaProducer;

    @Mock
    MinioService minioService;

    @Mock
    PiiEncryptionService piiEncryptionService;

    @InjectMocks
    SchemeServiceImpl schemeService;

    @BeforeEach
    void setUp() {
        TenantContext.setSchema(SCHEMA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void takesThePhoneNumberFromTheDatabaseEvenWhenTheTokenCarriesOne() {
        setJwt(Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("tenant_state_code", TENANT_CODE)
                .claim("email", "operator@example.com")
                .claim("phone_number", CLAIM_PHONE)
                .build());

        when(schemeDbRepository.findUserIdByEmail(SCHEMA, "operator@example.com")).thenReturn(10);
        when(schemeDbRepository.findUserPhoneNumberById(SCHEMA, 10)).thenReturn("enc:" + STORED_PHONE);
        when(piiEncryptionService.safeDecrypt("enc:" + STORED_PHONE)).thenReturn(STORED_PHONE);
        when(schemeDbRepository.listSchemesWithYesterdayFinalReadingForUser(SCHEMA, 10, null, 0, 10))
                .thenReturn(List.of(row()));
        when(schemeDbRepository.countSchemesWithYesterdayFinalReadingForUser(SCHEMA, 10, null)).thenReturn(1L);

        PageResponseDTO<SchemeYesterdayFinalReadingDTO> page =
                schemeService.listSchemesWithYesterdayFinalReading(TENANT_CODE, 0, 10, null);

        assertThat(page.getContent()).singleElement()
                .extracting(SchemeYesterdayFinalReadingDTO::getPhoneNumber)
                .isEqualTo(STORED_PHONE);
        // The encrypted column is read unconditionally: the claim must not short-circuit it.
        verify(schemeDbRepository).findUserPhoneNumberById(SCHEMA, 10);
    }

    private static SchemeYesterdayFinalReadingDTO row() {
        return SchemeYesterdayFinalReadingDTO.builder()
                .schemeId(1)
                .schemeName("Scheme One")
                .yesterdayFinalReading(new BigDecimal("120.5"))
                .build();
    }

    private static void setJwt(Jwt jwt) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, Collections.emptyList()));
        SecurityContextHolder.setContext(context);
    }
}
