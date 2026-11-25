package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.request.WelcomeMessageRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.WelcomeMessageResponseDTO;
import org.arghyam.jalsoochak.user.event.UserEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.repository.LanguageCatalogRepository;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserLanguagePreferenceRepository;
import org.arghyam.jalsoochak.user.repository.UserPhoneRow;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.WhatsAppPreferredLanguageService.PreferredLanguage;
import org.arghyam.jalsoochak.user.service.serviceImpl.WelcomeMessageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WelcomeMessageServiceImpl")
class WelcomeMessageServiceImplTest {

    @Mock private UserTenantRepository userTenantRepository;
    @Mock private UserCommonRepository userCommonRepository;
    @Mock private UserEventPublisher userEventPublisher;
    @Mock private UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    @Mock private LanguageCatalogRepository languageCatalogRepository;
    @Mock private WhatsAppPreferredLanguageService whatsAppPreferredLanguageService;

    private WelcomeMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WelcomeMessageServiceImpl(userTenantRepository, userCommonRepository, userEventPublisher,
                userLanguagePreferenceRepository, languageCatalogRepository, whatsAppPreferredLanguageService);
    }

    /** Makes the user stream yield the given users, in order. */
    private void givenUsers(UserPhoneRow... users) {
        doAnswer(invocation -> {
            Consumer<UserPhoneRow> consumer = invocation.getArgument(4);
            Arrays.stream(users).forEach(consumer);
            return null;
        }).when(userTenantRepository).streamUsersByRolesAndOnboardingWindow(anyString(), any(), any(), any(), any());
    }

    private static UserPhoneRow operator(long id, String phone) {
        return new UserPhoneRow(id, phone, "PUMP_OPERATOR");
    }

    private static UserPhoneRow officer(long id, String phone) {
        return new UserPhoneRow(id, phone, "SECTION_OFFICER");
    }

    private static Authentication stateAdminAuth(String tenantCode) {
        Authentication auth = mock(Authentication.class);
        doReturn(List.of(
                new SimpleGrantedAuthority("TENANT_" + tenantCode.toUpperCase()),
                new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))).when(auth).getAuthorities();
        return auth;
    }

    private static Authentication superUserAuth() {
        Authentication auth = mock(Authentication.class);
        doReturn(List.of(
                new SimpleGrantedAuthority("ROLE_SUPER_USER"))).when(auth).getAuthorities();
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
            WelcomeMessageResponseDTO resp = service.sendWelcomeMessages("mp", validRequest("SECTION_OFFICER"), auth);
            verify(userCommonRepository).findTenantIdByStateCode("mp");
            assertThat(resp.totalPhones()).isZero();
            assertThat(resp.message()).contains("No matching users");
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
            // streamUsersByRolesAndOnboardingWindow does nothing (no invocations of callback)

            WelcomeMessageResponseDTO resp = service.sendWelcomeMessages("mp", validRequest("SECTION_OFFICER"), auth);

            assertThat(resp.totalPhones()).isZero();
            assertThat(resp.batches()).isZero();
            assertThat(resp.message()).contains("No matching users");
        }

        @Test
        @DisplayName("publishes events and returns correct counts for matched users")
        void publishesEventsForMatchedUsers() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            givenUsers(officer(1, "91XXXXXXXXX1"), officer(2, "91XXXXXXXXX2"), officer(3, "91XXXXXXXXX3"));

            WelcomeMessageResponseDTO resp = service.sendWelcomeMessages("mp", validRequest("SECTION_OFFICER"), auth);

            assertThat(resp.totalPhones()).isEqualTo(3);
            assertThat(resp.batches()).isEqualTo(1);
            assertThat(resp.message()).contains("3");
            verify(userEventPublisher).publishAdminWelcomeMessages(
                    "mp", 1, List.of("91XXXXXXXXX1", "91XXXXXXXXX2", "91XXXXXXXXX3"));
        }

        @Test
        @DisplayName("splits more than 1000 users into batches of 1000")
        void splitsIntoBatchesOf1000() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            UserPhoneRow[] users = new UserPhoneRow[1001];
            for (int i = 0; i < users.length; i++) {
                users[i] = officer(i, String.format("91XXXXX%05d", i));
            }
            givenUsers(users);

            WelcomeMessageResponseDTO resp = service.sendWelcomeMessages("mp", validRequest("SECTION_OFFICER"), auth);

            assertThat(resp.totalPhones()).isEqualTo(1001);
            assertThat(resp.batches()).isEqualTo(2);
            verify(userEventPublisher).publishAdminWelcomeMessages(eq("mp"), eq(1),
                    org.mockito.ArgumentMatchers.argThat((List<String> phones) -> phones.size() == 1000));
            verify(userEventPublisher).publishAdminWelcomeMessages("mp", 1, List.of("91XXXXX01000"));
        }
    }

    @Nested
    @DisplayName("pump operator language before the welcome")
    class LanguageBeforeWelcome {

        private static final PreferredLanguage PUNJABI_DEFAULT = new PreferredLanguage(1, 9, "Punjabi");

        private Authentication auth;

        @BeforeEach
        void tenant() {
            auth = stateAdminAuth("PB");
            when(userCommonRepository.findTenantIdByStateCode("pb")).thenReturn(Optional.of(5));
        }

        @Test
        @DisplayName("sends each operator's saved language before the welcome, grouped by language")
        void sendsSavedLanguagesBeforeWelcome() {
            givenUsers(operator(1, "91XXXXXXXXX1"), operator(2, "91XXXXXXXXX2"), operator(3, "91XXXXXXXXX3"));
            when(userLanguagePreferenceRepository.findLanguageValues(eq("tenant_pb"), any())).thenReturn(Map.of(
                    "91XXXXXXXXX1", "Hindi", "91XXXXXXXXX2", "Punjabi", "91XXXXXXXXX3", "Hindi"));
            when(languageCatalogRepository.findLanguageIdByAlias("Hindi")).thenReturn(Optional.of(3));
            when(languageCatalogRepository.findLanguageIdByAlias("Punjabi")).thenReturn(Optional.of(9));

            service.sendWelcomeMessages("pb", validRequest("PUMP_OPERATOR"), auth);

            InOrder order = inOrder(userEventPublisher);
            order.verify(userEventPublisher).publishLanguageUpdates("pb", 5, "3", List.of("91XXXXXXXXX1", "91XXXXXXXXX3"));
            order.verify(userEventPublisher).publishLanguageUpdates("pb", 5, "9", List.of("91XXXXXXXXX2"));
            order.verify(userEventPublisher).publishAdminWelcomeMessages(
                    "pb", 5, List.of("91XXXXXXXXX1", "91XXXXXXXXX2", "91XXXXXXXXX3"));
            verifyNoInteractions(whatsAppPreferredLanguageService);
            verify(userLanguagePreferenceRepository, never()).setLanguageWhereUnset(any(), any(), any());
            verify(userTenantRepository, never()).updateUserLanguageId(any(), any(), any(Integer.class));
        }

        @Test
        @DisplayName("gives an operator with no saved language the tenant default, in the database and before the welcome")
        void givesDefaultToOperatorsWithoutLanguage() {
            givenUsers(operator(1, "91XXXXXXXXX1"), operator(2, "91XXXXXXXXX2"));
            when(userLanguagePreferenceRepository.findLanguageValues(eq("tenant_pb"), any()))
                    .thenReturn(Map.of("91XXXXXXXXX1", "Hindi"));
            when(languageCatalogRepository.findLanguageIdByAlias("Hindi")).thenReturn(Optional.of(3));
            when(whatsAppPreferredLanguageService.resolvePreferredLanguage(5, "tenant_pb")).thenReturn(PUNJABI_DEFAULT);

            service.sendWelcomeMessages("pb", validRequest("PUMP_OPERATOR"), auth);

            verify(userTenantRepository).updateUserLanguageId("tenant_pb", 2L, 1);
            verify(userTenantRepository, never()).updateUserLanguageId("tenant_pb", 1L, 1);
            verify(userLanguagePreferenceRepository).setLanguageWhereUnset("tenant_pb", List.of("91XXXXXXXXX2"), "Punjabi");
            InOrder order = inOrder(userEventPublisher);
            order.verify(userEventPublisher).publishLanguageUpdates("pb", 5, "3", List.of("91XXXXXXXXX1"));
            order.verify(userEventPublisher).publishLanguageUpdates("pb", 5, "9", List.of("91XXXXXXXXX2"));
            order.verify(userEventPublisher).publishAdminWelcomeMessages(
                    "pb", 5, List.of("91XXXXXXXXX1", "91XXXXXXXXX2"));
        }

        @Test
        @DisplayName("sends a saved language and the default that share an id as one update")
        void mergesSavedAndDefaultOfTheSameLanguage() {
            givenUsers(operator(1, "91XXXXXXXXX1"), operator(2, "91XXXXXXXXX2"));
            when(userLanguagePreferenceRepository.findLanguageValues(eq("tenant_pb"), any()))
                    .thenReturn(Map.of("91XXXXXXXXX1", "Punjabi"));
            when(languageCatalogRepository.findLanguageIdByAlias("Punjabi")).thenReturn(Optional.of(9));
            when(whatsAppPreferredLanguageService.resolvePreferredLanguage(5, "tenant_pb")).thenReturn(PUNJABI_DEFAULT);

            service.sendWelcomeMessages("pb", validRequest("PUMP_OPERATOR"), auth);

            verify(userEventPublisher).publishLanguageUpdates("pb", 5, "9", List.of("91XXXXXXXXX1", "91XXXXXXXXX2"));
        }

        @Test
        @DisplayName("writes no saved language when the tenant default has none, but still language_id and the update")
        void writesNoSavedLanguageWithoutDefaultValue() {
            givenUsers(operator(1, "91XXXXXXXXX1"));
            when(userLanguagePreferenceRepository.findLanguageValues(eq("tenant_pb"), any())).thenReturn(Map.of());
            when(whatsAppPreferredLanguageService.resolvePreferredLanguage(5, "tenant_pb"))
                    .thenReturn(new PreferredLanguage(1, 1, null));

            service.sendWelcomeMessages("pb", validRequest("PUMP_OPERATOR"), auth);

            verify(userTenantRepository).updateUserLanguageId("tenant_pb", 1L, 1);
            verify(userLanguagePreferenceRepository, never()).setLanguageWhereUnset(any(), any(), any());
            verify(userEventPublisher).publishLanguageUpdates("pb", 5, "1", List.of("91XXXXXXXXX1"));
        }

        @Test
        @DisplayName("sends no language for a saved language the catalog does not know, but still the welcome")
        void skipsUnknownSavedLanguage() {
            givenUsers(operator(1, "91XXXXXXXXX1"));
            when(userLanguagePreferenceRepository.findLanguageValues(eq("tenant_pb"), any()))
                    .thenReturn(Map.of("91XXXXXXXXX1", "Klingon"));
            when(languageCatalogRepository.findLanguageIdByAlias("Klingon")).thenReturn(Optional.empty());

            service.sendWelcomeMessages("pb", validRequest("PUMP_OPERATOR"), auth);

            verify(userEventPublisher, never()).publishLanguageUpdates(any(), any(), any(), any());
            verify(userEventPublisher).publishAdminWelcomeMessages("pb", 5, List.of("91XXXXXXXXX1"));
            verifyNoInteractions(whatsAppPreferredLanguageService);
            verify(userTenantRepository, never()).updateUserLanguageId(any(), any(), any(Integer.class));
        }

        @Test
        @DisplayName("looks up only pump operators' languages; officers get the welcome alone")
        void looksUpOnlyPumpOperators() {
            givenUsers(officer(1, "91XXXXXXXXX1"), operator(2, "91XXXXXXXXX2"));
            when(userLanguagePreferenceRepository.findLanguageValues(eq("tenant_pb"), any()))
                    .thenReturn(Map.of("91XXXXXXXXX2", "Punjabi"));
            when(languageCatalogRepository.findLanguageIdByAlias("Punjabi")).thenReturn(Optional.of(9));

            service.sendWelcomeMessages("pb", validRequest("SECTION_OFFICER", "PUMP_OPERATOR"), auth);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<Collection<String>> looked = org.mockito.ArgumentCaptor.forClass(Collection.class);
            verify(userLanguagePreferenceRepository).findLanguageValues(eq("tenant_pb"), looked.capture());
            assertThat(looked.getValue()).containsExactly("91XXXXXXXXX2");
            verify(userEventPublisher).publishLanguageUpdates("pb", 5, "9", List.of("91XXXXXXXXX2"));
            verify(userEventPublisher).publishAdminWelcomeMessages("pb", 5, List.of("91XXXXXXXXX1", "91XXXXXXXXX2"));
        }

        @Test
        @DisplayName("touches no language when only officers are welcomed")
        void noLanguageWorkForOfficers() {
            givenUsers(officer(1, "91XXXXXXXXX1"));

            service.sendWelcomeMessages("pb", validRequest("SECTION_OFFICER"), auth);

            verifyNoInteractions(userLanguagePreferenceRepository, languageCatalogRepository,
                    whatsAppPreferredLanguageService);
            verify(userEventPublisher, never()).publishLanguageUpdates(any(), any(), any(), any());
            verify(userEventPublisher).publishAdminWelcomeMessages("pb", 5, List.of("91XXXXXXXXX1"));
        }

        @Test
        @DisplayName("resolves the tenant default and each catalog language once per request")
        void resolvesOncePerRequest() {
            UserPhoneRow[] users = new UserPhoneRow[1002];
            for (int i = 0; i < users.length; i++) {
                users[i] = operator(i, String.format("91XXXXX%05d", i));
            }
            givenUsers(users);
            when(userLanguagePreferenceRepository.findLanguageValues(eq("tenant_pb"), any())).thenAnswer(invocation -> {
                Collection<String> phones = invocation.getArgument(1);
                return Map.of(phones.iterator().next(), "Hindi");
            });
            when(languageCatalogRepository.findLanguageIdByAlias("Hindi")).thenReturn(Optional.of(3));
            when(whatsAppPreferredLanguageService.resolvePreferredLanguage(5, "tenant_pb")).thenReturn(PUNJABI_DEFAULT);

            service.sendWelcomeMessages("pb", validRequest("PUMP_OPERATOR"), auth);

            verify(userLanguagePreferenceRepository, org.mockito.Mockito.times(2)).findLanguageValues(eq("tenant_pb"), any());
            verify(languageCatalogRepository).findLanguageIdByAlias("Hindi");
            verify(whatsAppPreferredLanguageService).resolvePreferredLanguage(5, "tenant_pb");
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

            WelcomeMessageResponseDTO resp = service.sendWelcomeMessages("mp", req, auth);

            assertThat(resp.totalPhones()).isZero();
            assertThat(resp.message()).contains("No matching users");
            verify(userTenantRepository).streamUsersByRolesAndOnboardingWindow(
                    anyString(), any(), any(java.time.Instant.class), any(), any());
        }

        @Test
        @DisplayName("accepts yyyy-MM-dd date as onboardedAfter")
        void acceptsDateString() {
            Authentication auth = stateAdminAuth("MP");
            when(userCommonRepository.findTenantIdByStateCode("mp")).thenReturn(Optional.of(1));
            WelcomeMessageRequestDTO req = validRequest("SECTION_OFFICER");
            req.setOnboardedAfter("2024-01-01");

            WelcomeMessageResponseDTO resp = service.sendWelcomeMessages("mp", req, auth);

            assertThat(resp.totalPhones()).isZero();
            assertThat(resp.message()).contains("No matching users");
            verify(userTenantRepository).streamUsersByRolesAndOnboardingWindow(
                    anyString(), any(), any(java.time.Instant.class), any(), any());
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
