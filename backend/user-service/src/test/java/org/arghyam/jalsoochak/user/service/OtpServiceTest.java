package org.arghyam.jalsoochak.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.arghyam.jalsoochak.user.config.properties.OtpProperties;
import org.arghyam.jalsoochak.user.enums.OtpType;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.repository.OtpRepository;
import org.arghyam.jalsoochak.user.repository.records.OtpRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OtpService")
class OtpServiceTest {

    @Mock OtpRepository otpRepository;
    @Mock PiiEncryptionService piiEncryptionService;

    OtpProperties otpProperties;
    OtpService otpService;

    @BeforeEach
    void setUp() {
        otpProperties = new OtpProperties(10, 5, 60, 6, "WHATSAPP", null);
        otpService = new OtpService(otpRepository, piiEncryptionService, otpProperties);
    }

    @Nested
    @DisplayName("requestOtp")
    class RequestOtp {

        @Test
        @DisplayName("generates OTP when no active OTP exists")
        void generatesOtpWhenNoActiveOtp() {
            when(otpRepository.findActiveOtp(1L, 1, OtpType.LOGIN)).thenReturn(Optional.empty());
            when(piiEncryptionService.encrypt(anyString())).thenReturn("encrypted");

            String otp = otpService.requestOtp(1L, 1, OtpType.LOGIN);

            assertThat(otp).hasSize(6).matches("\\d{6}");
            verify(otpRepository).revokeActiveOtp(1L, 1, OtpType.LOGIN);
            verify(otpRepository).insertOtp(eq(1L), eq(1), eq(OtpType.LOGIN), eq("encrypted"), any(Instant.class));
        }

        @Test
        @DisplayName("allows new OTP when existing OTP is past cooldown")
        void allowsNewOtpAfterCooldown() {
            Instant createdAtOld = Instant.now().minus(90, ChronoUnit.SECONDS);
            OtpRow existing = new OtpRow(1L, "enc", 1, 1L, OtpType.LOGIN, 0, createdAtOld,
                    Instant.now().plus(8, ChronoUnit.MINUTES), null);
            when(otpRepository.findActiveOtp(1L, 1, OtpType.LOGIN)).thenReturn(Optional.of(existing));
            when(piiEncryptionService.encrypt(anyString())).thenReturn("encrypted");

            String otp = otpService.requestOtp(1L, 1, OtpType.LOGIN);

            assertThat(otp).hasSize(6);
            verify(otpRepository).revokeActiveOtp(1L, 1, OtpType.LOGIN);
        }

        @Test
        @DisplayName("returns cheat OTP when cheat is enabled")
        void returnsCheatOtpWhenEnabled() {
            OtpProperties cheatProps = new OtpProperties(10, 5, 60, 6, "WHATSAPP",
                    new OtpProperties.Cheat(true, "000000"));
            OtpService cheatService = new OtpService(otpRepository, piiEncryptionService, cheatProps);
            when(otpRepository.findActiveOtp(1L, 1, OtpType.LOGIN)).thenReturn(Optional.empty());
            when(piiEncryptionService.encrypt("000000")).thenReturn("encrypted");

            String otp = cheatService.requestOtp(1L, 1, OtpType.LOGIN);

            assertThat(otp).isEqualTo("000000");
            verify(otpRepository).insertOtp(eq(1L), eq(1), eq(OtpType.LOGIN), eq("encrypted"), any(Instant.class));
        }

        @Test
        @DisplayName("throws BadRequestException during cooldown period")
        void throwsDuringCooldown() {
            Instant recentCreatedAt = Instant.now().minus(10, ChronoUnit.SECONDS);
            OtpRow recent = new OtpRow(1L, "enc", 1, 1L, OtpType.LOGIN, 0, recentCreatedAt,
                    Instant.now().plus(9, ChronoUnit.MINUTES), null);
            when(otpRepository.findActiveOtp(1L, 1, OtpType.LOGIN)).thenReturn(Optional.of(recent));

            assertThatThrownBy(() -> otpService.requestOtp(1L, 1, OtpType.LOGIN))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Please wait");

            verify(otpRepository, never()).insertOtp(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("verifyOtp")
    class VerifyOtp {

        @Test
        @DisplayName("marks OTP used on correct input and returns OTP ID")
        void marksUsedOnCorrectOtp() {
            OtpRow row = new OtpRow(10L, "encrypted-123456", 1, 1L, OtpType.LOGIN, 0,
                    Instant.now().minus(1, ChronoUnit.MINUTES),
                    Instant.now().plus(9, ChronoUnit.MINUTES), null);
            when(otpRepository.findActiveOtpForUpdate(1L, 1, OtpType.LOGIN)).thenReturn(Optional.of(row));
            when(piiEncryptionService.decrypt("encrypted-123456")).thenReturn("123456");
            when(otpRepository.markUsed(10L)).thenReturn(true);

            Long consumedId = otpService.verifyOtp(1L, 1, OtpType.LOGIN, "123456");

            assertThat(consumedId).isEqualTo(10L);
            verify(otpRepository).markUsed(10L);
            verify(otpRepository, never()).incrementAttemptCount(any());
        }

        @Test
        @DisplayName("throws and increments attempt count on mismatch")
        void incrementsAttemptOnMismatch() {
            OtpRow row = new OtpRow(10L, "encrypted-123456", 1, 1L, OtpType.LOGIN, 0,
                    Instant.now().minus(1, ChronoUnit.MINUTES),
                    Instant.now().plus(9, ChronoUnit.MINUTES), null);
            when(otpRepository.findActiveOtpForUpdate(1L, 1, OtpType.LOGIN)).thenReturn(Optional.of(row));
            when(piiEncryptionService.decrypt("encrypted-123456")).thenReturn("123456");

            assertThatThrownBy(() -> otpService.verifyOtp(1L, 1, OtpType.LOGIN, "999999"))
                    .isInstanceOf(BadRequestException.class);

            verify(otpRepository).incrementAttemptCount(10L);
            verify(otpRepository, never()).markUsed(any());
        }

        @Test
        @DisplayName("throws when no active OTP exists")
        void throwsWhenNoActiveOtp() {
            when(otpRepository.findActiveOtpForUpdate(1L, 1, OtpType.LOGIN)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> otpService.verifyOtp(1L, 1, OtpType.LOGIN, "123456"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid or expired OTP");
        }

        @Test
        @DisplayName("throws when markUsed returns false (concurrent consumption race)")
        void throwsWhenMarkUsedReturnsFalse() {
            OtpRow row = new OtpRow(10L, "encrypted-123456", 1, 1L, OtpType.LOGIN, 0,
                    Instant.now().minus(1, ChronoUnit.MINUTES),
                    Instant.now().plus(9, ChronoUnit.MINUTES), null);
            when(otpRepository.findActiveOtpForUpdate(1L, 1, OtpType.LOGIN)).thenReturn(Optional.of(row));
            when(piiEncryptionService.decrypt("encrypted-123456")).thenReturn("123456");
            when(otpRepository.markUsed(10L)).thenReturn(false);

            assertThatThrownBy(() -> otpService.verifyOtp(1L, 1, OtpType.LOGIN, "123456"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid or expired OTP");

            verify(otpRepository).markUsed(10L);
            verify(otpRepository, never()).incrementAttemptCount(any());
        }

        @Test
        @DisplayName("reverts OTP consumption when revertOtpConsumption is called and OTP not expired")
        void revertsOtpConsumption() {
            when(otpRepository.revertConsumption(10L)).thenReturn(true);

            otpService.revertOtpConsumption(10L);

            verify(otpRepository).revertConsumption(10L);
        }

        @Test
        @DisplayName("logs warning when revertOtpConsumption finds OTP expired")
        void logsWarningWhenRevertFindsExpiredOtp() {
            when(otpRepository.revertConsumption(10L)).thenReturn(false);

            otpService.revertOtpConsumption(10L); // must not throw

            verify(otpRepository).revertConsumption(10L);
        }

        @Test
        @DisplayName("does not rethrow when revertConsumption throws a repository exception")
        void doesNotRethrowOnRepositoryException() {
            when(otpRepository.revertConsumption(10L)).thenThrow(new RuntimeException("DB unavailable"));

            otpService.revertOtpConsumption(10L); // must not throw

            verify(otpRepository).revertConsumption(10L);
        }

        @Test
        @DisplayName("throws when max attempts exceeded")
        void throwsWhenMaxAttemptsExceeded() {
            OtpRow row = new OtpRow(10L, "enc", 1, 1L, OtpType.LOGIN, 5,
                    Instant.now().minus(1, ChronoUnit.MINUTES),
                    Instant.now().plus(9, ChronoUnit.MINUTES), null);
            when(otpRepository.findActiveOtpForUpdate(1L, 1, OtpType.LOGIN)).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> otpService.verifyOtp(1L, 1, OtpType.LOGIN, "123456"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Maximum OTP attempts exceeded");

            verify(otpRepository, never()).incrementAttemptCount(any());
        }
    }
}
