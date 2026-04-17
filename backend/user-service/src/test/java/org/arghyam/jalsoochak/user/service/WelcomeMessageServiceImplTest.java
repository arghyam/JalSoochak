package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.request.WelcomeMessageRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.WelcomeMessageResponseDTO;
import org.arghyam.jalsoochak.user.event.UserEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.serviceImpl.WelcomeMessageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WelcomeMessageServiceImpl")
class WelcomeMessageServiceImplTest {

    @Mock private UserTenantRepository userTenantRepository;
    @Mock private UserCommonRepository userCommonRepository;
    @Mock private UserEventPublisher userEventPublisher;

    private WelcomeMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WelcomeMessageServiceImpl(userTenantRepository, userCommonRepository, userEventPublisher);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Authentication stateAdminAuth(String tenantCode) {
        Authentication auth = mock(Authentication.class);
        when(auth.getAuthorities()).thenReturn((java.util.Collection) List.of(
                new SimpleGrantedAuthority("TENANT_" + tenantCode.toUpperCase()),
                new SimpleGrantedAuthority("ROLE_STATE_ADMIN")));
        return auth;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Authentication superUserAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getAuthorities()).thenReturn((java.util.Collection) List.of(
                new SimpleGrantedAuthority("ROLE_SUPER_USER")));
        return auth;
    }

    private static WelcomeMessageRequestDTO validRequest(String... roles) {
        WelcomeMessageRequestDTO req = new WelcomeMessageRequestDTO();
        req.setType("welcome_template");
        req.setRoles(List.of(roles));
        return req;
    }

    @Nested
    @DisplayName("request validation")
    class RequestValidation {

        @Test
        @DisplayName("throws IllegalArgumentException when request is null")
        void throwsForNullRequest() {
            Authentication auth = mock(Authentication.class);
            assertThatThrownBy(() -> service.sendWelcomeMessages("mp", null, auth))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for unsupported type")
        void throwsForUnsupportedType() {
            Authentication auth = mock(Authentication.class);
            WelcomeMessageRequestDTO req = new WelcomeMessageRequestDTO();
            req.setType("unknown_type");
            req.setRoles(List.of("SECTION_OFFICER"));

            assertThatThrownBy(() -> service.sendWelcomeMessages("mp", req, auth))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("welcome_template");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when roles list is empty after normalization")
        void throwsForEmptyRoles() {
            Authentication auth = mock(Authentication.class);
            WelcomeMessageRequestDTO req = new WelcomeMessageRequestDTO();
            req.setType("welcome_template");
            req.setRoles(List.of("  ", ""));

            assertThatThrownBy(() -> service.sendWelcomeMessages("mp", req, auth))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("role");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when onboardedBefore is not after onboardedAfter")
        void throwsForInvalidDateRange() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            WelcomeMessageRequestDTO req = validRequest("SECTION_OFFICER");
            req.setOnboardedAfter("2024-06-01");
            req.setOnboardedBefore("2024-05-01");

            assertThatThrownBy(() -> service.sendWelcomeMessages("mp", req, auth))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("onboardedBefore");
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("throws ForbiddenAccessException when state admin requests different tenant")
        void throwsForWrongTenant() {
            Authentication auth = stateAdminAuth("TR");
            assertThatThrownBy(() ->
                    service.sendWelcomeMessages("mp", validRequest("SECTION_OFFICER"), auth))
                    .isInstanceOf(ForbiddenAccessException.class);
        }

        @Test
        @DisplayName("allows SUPER_USER to act on any tenant")
        void allowsSuperUserAnyTenant() {
            Authentication auth = superUserAuth();
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            // No phones returned → no events
            service.sendWelcomeMessages("mp", validRequest("SECTION_OFFICER"), auth);
            verify(userCommonRepository).findTenantIdByStateCode("mp");
        }
    }

    @Nested
    @DisplayName("tenant resolution")
    class TenantResolution {

        @Test
        @DisplayName("throws ResourceNotFoundException when tenant not found")
        void throwsWhenTenantNotFound() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.sendWelcomeMessages("mp", validRequest("SECTION_OFFICER"), auth))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("event publishing")
    class EventPublishing {

        @Test
        @DisplayName("returns zero counts when no matching users")
        void zeroCountsForNoUsers() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            // streamPhonesByRolesAndOnboardingWindow does nothing (no invocations of callback)

            WelcomeMessageResponseDTO resp = service.sendWelcomeMessages("mp", validRequest("SECTION_OFFICER"), auth);

            assertThat(resp.totalPhones()).isZero();
            assertThat(resp.batches()).isZero();
            assertThat(resp.message()).contains("No matching users");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("publishes events and returns correct counts for matched users")
        void publishesEventsForMatchedUsers() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));

            // Simulate streaming 3 phones via the callback
            org.mockito.stubbing.Answer<Void> streamAnswer = invocation -> {
                Consumer<String> cb = invocation.getArgument(4);
                cb.accept("919876543001");
                cb.accept("919876543002");
                cb.accept("919876543003");
                return null;
            };
            org.mockito.Mockito.doAnswer(streamAnswer)
                    .when(userTenantRepository)
                    .streamPhonesByRolesAndOnboardingWindow(anyString(), any(), any(), any(), any());

            WelcomeMessageResponseDTO resp = service.sendWelcomeMessages("mp", validRequest("SECTION_OFFICER"), auth);

            assertThat(resp.totalPhones()).isEqualTo(3);
            assertThat(resp.batches()).isEqualTo(1);
            assertThat(resp.message()).contains("3");
            verify(userEventPublisher).publishAdminWelcomeMessages(anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("date/time parsing")
    class DateTimeParsing {

        @Test
        @DisplayName("accepts ISO-8601 timestamp as onboardedAfter")
        void acceptsIsoTimestamp() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            WelcomeMessageRequestDTO req = validRequest("SECTION_OFFICER");
            req.setOnboardedAfter("2024-01-01T00:00:00Z");

            // Should not throw
            service.sendWelcomeMessages("mp", req, auth);
        }

        @Test
        @DisplayName("accepts yyyy-MM-dd date as onboardedAfter")
        void acceptsDateString() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            WelcomeMessageRequestDTO req = validRequest("SECTION_OFFICER");
            req.setOnboardedAfter("2024-01-01");

            // Should not throw
            service.sendWelcomeMessages("mp", req, auth);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for unparseable date string")
        void throwsForInvalidDateFormat() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            WelcomeMessageRequestDTO req = validRequest("SECTION_OFFICER");
            req.setOnboardedAfter("not-a-date");

            assertThatThrownBy(() -> service.sendWelcomeMessages("mp", req, auth))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid date/time format");
        }
    }
}
