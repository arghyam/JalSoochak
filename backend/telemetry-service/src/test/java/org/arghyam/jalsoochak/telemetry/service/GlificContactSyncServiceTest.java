package org.arghyam.jalsoochak.telemetry.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mirroring an operator's language choice into their Glific contact record.
 *
 * <p>This is a best-effort background sync: the preference is already stored locally, so every
 * failure here is swallowed rather than surfaced to the operator mid-flow.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificContactSyncService")
class GlificContactSyncServiceTest {

    private static final String PHONE = "919999900001";
    private static final String BASE_URL = "https://api.arghyam.glific.com";
    private static final String SESSION_URL = BASE_URL + "/api/v1/session";
    private static final String GRAPHQL_URL = BASE_URL + "/api";

    @Mock
    private RestTemplate restTemplate;

    /** Runs submitted work inline so the test observes the completed sync. */
    private final Executor inlineExecutor = Runnable::run;

    private GlificContactSyncService service;

    @BeforeEach
    void setUp() {
        service = new GlificContactSyncService(restTemplate, inlineExecutor);
        configure(true, "919000000000", "password");
    }

    private void configure(boolean enabled, String phone, String password) {
        ReflectionTestUtils.setField(service, "glificSyncEnabled", enabled);
        ReflectionTestUtils.setField(service, "glificBaseUrl", BASE_URL);
        ReflectionTestUtils.setField(service, "glificUserPhone", phone);
        ReflectionTestUtils.setField(service, "glificUserPassword", password);
    }

    private void stubLogin(String accessToken) {
        when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("access_token", accessToken))));
    }

    private void stubContactLookup(String contactId) {
        when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("contacts(filter"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("data",
                        Map.of("contacts", List.of(Map.of("id", contactId))))));
    }

    private void stubUpdate(Map<String, Object> body) {
        when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("updateContact"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    @SuppressWarnings("unchecked")
    private static HttpEntity<Map<String, Object>> argThatIsQuery(String fragment) {
        return org.mockito.ArgumentMatchers.argThat(entity ->
                entity != null && entity.getBody() != null
                        && String.valueOf(((Map<String, Object>) entity.getBody()).get("query")).contains(fragment));
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        void doesNothingWhenSyncIsDisabled() {
            configure(false, "919000000000", "password");

            service.syncContactLanguageAsync(PHONE, "Hindi");

            verifyNoInteractions(restTemplate);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "+"})
        void doesNothingForAnUnusablePhoneNumber(String phone) {
            service.syncContactLanguageAsync(phone, "Hindi");

            verifyNoInteractions(restTemplate);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "Klingon"})
        void doesNothingForALanguageGlificDoesNotKnow(String language) {
            service.syncContactLanguageAsync(PHONE, language);

            verifyNoInteractions(restTemplate);
        }

        @Test
        void doesNothingWhenCredentialsAreNotConfigured() {
            configure(true, "", "password");
            service.syncContactLanguageAsync(PHONE, "Hindi");

            configure(true, "919000000000", "  ");
            service.syncContactLanguageAsync(PHONE, "Hindi");

            verifyNoInteractions(restTemplate);
        }
    }

    @Nested
    @DisplayName("language mapping")
    class LanguageMapping {

        @ParameterizedTest(name = "\"{0}\" -> Glific language {1}")
        @CsvSource({
                "English,1", "en,1", "ENGLISH,1",
                "Hindi,2", "hi,2",
                "Tamil,3", "Kannada,4", "Malayalam,5", "Telugu,6", "Odia,7",
                "Assamese,8", "as,8",
                "Gujarati,9", "Bengali,10", "Punjabi,11", "Marathi,12", "Urdu,13",
                "Spanish,14", "French,16", "Swahili,17", "Malay,20", "Gondi,21", "Indonesian,22"
        })
        void mapsEveryKnownLanguageNameToItsGlificId(String language, int expectedId) {
            stubLogin("token");
            stubContactLookup("55");
            stubUpdate(Map.of("data", Map.of("updateContact",
                    Map.of("contact", Map.of("id", "55", "language", Map.of("id", expectedId))))));

            service.syncContactLanguageAsync(PHONE, language);

            assertThat(capturedMutation()).contains("language_id: " + expectedId);
        }

        @Test
        void normalisesPunctuationInTheLanguageName() {
            stubLogin("token");
            stubContactLookup("55");
            stubUpdate(Map.of("data", Map.of("updateContact",
                    Map.of("contact", Map.of("id", "55", "language", Map.of("id", 15))))));

            service.syncContactLanguageAsync(PHONE, "Sign-Language");

            assertThat(capturedMutation()).contains("language_id: 15");
        }

        @Test
        void acceptsANumericLanguageIdDirectly() {
            stubLogin("token");
            stubContactLookup("55");
            stubUpdate(Map.of("data", Map.of("updateContact",
                    Map.of("contact", Map.of("id", "55", "language", Map.of("id", 8))))));

            service.syncContactLanguageAsync(PHONE, "8");

            assertThat(capturedMutation()).contains("language_id: 8");
        }

        @SuppressWarnings("unchecked")
        private String capturedMutation() {
            ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate, org.mockito.Mockito.atLeastOnce())
                    .postForEntity(eq(GRAPHQL_URL), captor.capture(), eq(Map.class));
            return captor.getAllValues().stream()
                    .map(e -> String.valueOf(e.getBody().get("query")))
                    .filter(q -> q.contains("updateContact"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No updateContact mutation was sent"));
        }
    }

    @Nested
    @DisplayName("sync")
    class Sync {

        @Test
        void logsInLooksUpTheContactAndUpdatesItsLanguage() {
            stubLogin("token");
            stubContactLookup("55");
            stubUpdate(Map.of("data", Map.of("updateContact",
                    Map.of("contact", Map.of("id", "55", "language", Map.of("id", 2))))));

            service.syncContactLanguageAsync(PHONE, "Hindi");

            verify(restTemplate).postForEntity(eq(SESSION_URL), any(), eq(Map.class));
            verify(restTemplate).postForEntity(eq(GRAPHQL_URL), argThatIsQuery("updateContact"), eq(Map.class));
        }

        @Test
        void stripsALeadingPlusBeforeLookingUpTheContact() {
            stubLogin("token");
            stubContactLookup("55");
            stubUpdate(Map.of("data", Map.of("updateContact",
                    Map.of("contact", Map.of("id", "55", "language", Map.of("id", 2))))));

            service.syncContactLanguageAsync("+" + PHONE, "Hindi");

            verify(restTemplate).postForEntity(eq(GRAPHQL_URL),
                    argThatIsQuery("phone: \"" + PHONE + "\""), eq(Map.class));
        }

        @Test
        void retriesTheLookupWithAPlusPrefixedPhone() {
            stubLogin("token");
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("contacts(filter"), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("contacts", List.of()))))
                    .thenReturn(ResponseEntity.ok(Map.of("data",
                            Map.of("contacts", List.of(Map.of("id", "55"))))));
            stubUpdate(Map.of("data", Map.of("updateContact",
                    Map.of("contact", Map.of("id", "55", "language", Map.of("id", 2))))));

            service.syncContactLanguageAsync(PHONE, "Hindi");

            verify(restTemplate).postForEntity(eq(GRAPHQL_URL), argThatIsQuery("updateContact"), eq(Map.class));
        }

        @Test
        void abortsWhenLoginFails() {
            when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));

            service.syncContactLanguageAsync(PHONE, "Hindi");

            verify(restTemplate, never()).postForEntity(eq(GRAPHQL_URL), any(), eq(Map.class));
        }

        @Test
        void abortsWhenTheLoginBodyIsUnusable() {
            when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(null));
            service.syncContactLanguageAsync(PHONE, "Hindi");

            when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("unexpected", "shape")));
            service.syncContactLanguageAsync(PHONE, "Hindi");

            stubLogin("   ");
            service.syncContactLanguageAsync(PHONE, "Hindi");

            verify(restTemplate, never()).postForEntity(eq(GRAPHQL_URL), any(), eq(Map.class));
        }

        @Test
        void abortsWhenTheContactCannotBeFound() {
            stubLogin("token");
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("contacts(filter"), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("contacts", List.of()))));

            service.syncContactLanguageAsync(PHONE, "Hindi");

            verify(restTemplate, never())
                    .postForEntity(eq(GRAPHQL_URL), argThatIsQuery("updateContact"), eq(Map.class));
        }

        @Test
        void abortsWhenTheContactLookupReturnsAnUnusableShape() {
            stubLogin("token");
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("contacts(filter"), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.BAD_GATEWAY))
                    .thenReturn(ResponseEntity.ok(null))
                    .thenReturn(ResponseEntity.ok(Map.of("errors", List.of(Map.of("message", "nope")))))
                    .thenReturn(ResponseEntity.ok(Map.of("data", "not-a-map")))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("contacts", "not-a-list"))))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("contacts", List.of("not-a-map")))));

            assertThatCode(() -> {
                for (int i = 0; i < 6; i++) {
                    service.syncContactLanguageAsync(PHONE, "Hindi");
                }
            }).doesNotThrowAnyException();

            verify(restTemplate, never())
                    .postForEntity(eq(GRAPHQL_URL), argThatIsQuery("updateContact"), eq(Map.class));
        }

        @Test
        void toleratesAnUnusableUpdateResponse() {
            stubLogin("token");
            stubContactLookup("55");
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("updateContact"), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR))
                    .thenReturn(ResponseEntity.ok(null))
                    .thenReturn(ResponseEntity.ok(Map.of("errors", List.of(Map.of("message", "nope")))))
                    .thenReturn(ResponseEntity.ok(Map.of("data", "not-a-map")))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("updateContact", "not-a-map"))))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("updateContact",
                            Map.of("contact", "not-a-map")))))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("updateContact",
                            Map.of("contact", Map.of("language", "not-a-map"))))))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("updateContact",
                            Map.of("contact", Map.of("language", Map.of("id", "not-a-number")))))))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("updateContact",
                            Map.of("contact", Map.of("language", Map.of("id", 99)))))));

            assertThatCode(() -> {
                for (int i = 0; i < 9; i++) {
                    service.syncContactLanguageAsync(PHONE, "Hindi");
                }
            }).doesNotThrowAnyException();
        }

        @Test
        void swallowsATransportFailure() {
            when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                    .thenThrow(new RestClientException("connection reset"));

            assertThatCode(() -> service.syncContactLanguageAsync(PHONE, "Hindi"))
                    .doesNotThrowAnyException();
        }

        @Test
        void submitsTheWorkToTheConfiguredExecutor() {
            var neverRuns = new GlificContactSyncService(restTemplate, command -> { /* dropped */ });
            ReflectionTestUtils.setField(neverRuns, "glificSyncEnabled", true);
            ReflectionTestUtils.setField(neverRuns, "glificBaseUrl", BASE_URL);
            ReflectionTestUtils.setField(neverRuns, "glificUserPhone", "919000000000");
            ReflectionTestUtils.setField(neverRuns, "glificUserPassword", "password");

            neverRuns.syncContactLanguageAsync(PHONE, "Hindi");

            verifyNoInteractions(restTemplate);
        }
    }
}
