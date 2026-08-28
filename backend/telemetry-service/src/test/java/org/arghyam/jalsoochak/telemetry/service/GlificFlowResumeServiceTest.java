package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Resuming the operator's Glific flow once an async reading has been processed.
 *
 * <p>Every failure here is non-fatal by design — the reading is already persisted, so a login,
 * lookup, or mutation failure must be logged and swallowed rather than propagated.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificFlowResumeService")
class GlificFlowResumeServiceTest {

    private static final String CONTACT = "919999900001";
    private static final String JOB_ID = "job-1";
    private static final String BASE_URL = "https://api.staging.glific.com";
    private static final String SESSION_URL = BASE_URL + "/api/v1/session";
    private static final String GRAPHQL_URL = BASE_URL + "/api";

    @Mock
    private RestTemplate restTemplate;

    private GlificFlowResumeService service;

    @BeforeEach
    void setUp() {
        service = new GlificFlowResumeService(restTemplate, new ObjectMapper());
        configure(true, BASE_URL, "919000000000", "password", "37172");
    }

    private void configure(boolean enabled, String baseUrl, String phone, String password, String flowId) {
        ReflectionTestUtils.setField(service, "resumeEnabled", enabled);
        ReflectionTestUtils.setField(service, "glificBaseUrl", baseUrl);
        ReflectionTestUtils.setField(service, "glificUserPhone", phone);
        ReflectionTestUtils.setField(service, "glificUserPassword", password);
        ReflectionTestUtils.setField(service, "flowId", flowId);
    }

    private static CreateReadingResponse result() {
        return CreateReadingResponse.builder()
                .success(true)
                .message("Reading recorded.")
                .correlationId("corr-1")
                .meterReading(new BigDecimal("1234"))
                .qualityStatus("ACCEPTED")
                .build();
    }

    private void stubLogin(String accessToken) {
        when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("access_token", accessToken))));
    }

    private void stubContactLookup(String glificContactId) {
        when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("contactByPhone"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("data",
                        Map.of("contactByPhone", Map.of("contact", Map.of("id", glificContactId))))));
    }

    @SuppressWarnings("unchecked")
    private void stubResumeMutation(Map<String, Object> body) {
        when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("resumeContactFlow"), eq(Map.class)))
                .thenReturn((ResponseEntity<Map>) (ResponseEntity<?>) ResponseEntity.ok(body));
    }

    /** Matches the GraphQL POST whose "query" field mentions the given operation. */
    @SuppressWarnings("unchecked")
    private static HttpEntity<Map<String, Object>> argThatIsQuery(String operation) {
        return org.mockito.ArgumentMatchers.argThat(entity ->
                entity != null
                        && entity.getBody() != null
                        && String.valueOf(((Map<String, Object>) entity.getBody()).get("query")).contains(operation));
    }

    /**
     * The GraphQL endpoint serves both the contact lookup and the resume mutation, so a captor alone
     * cannot tell them apart — this captures every call and picks the one for {@code operation}.
     */
    @SuppressWarnings("unchecked")
    private HttpEntity<Map<String, Object>> capturedGraphQlRequest(String operation) {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.atLeastOnce())
                .postForEntity(eq(GRAPHQL_URL), captor.capture(), eq(Map.class));
        return captor.getAllValues().stream()
                .filter(entity -> entity.getBody() != null
                        && String.valueOf(entity.getBody().get("query")).contains(operation))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No GraphQL request issued for " + operation));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variablesOf(HttpEntity<Map<String, Object>> request) {
        return (Map<String, Object>) request.getBody().get("variables");
    }

    @Nested
    @DisplayName("configuration guards")
    class ConfigurationGuards {

        @Test
        void doesNothingWhenResumeIsDisabled() {
            configure(false, BASE_URL, "919000000000", "password", "37172");

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verifyNoInteractions(restTemplate);
        }

        @Test
        void doesNothingWhenTheContactIdIsMissing() {
            service.resumeReadingsFlow(null, JOB_ID, result());
            service.resumeReadingsFlow("  ", JOB_ID, result());

            verifyNoInteractions(restTemplate);
        }

        @Test
        void doesNothingWhenTheFlowIdIsNotConfigured() {
            configure(true, BASE_URL, "919000000000", "password", "  ");

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verifyNoInteractions(restTemplate);
        }

        @Test
        void doesNothingWhenCredentialsAreNotConfigured() {
            configure(true, BASE_URL, "", "password", "37172");
            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            configure(true, BASE_URL, "919000000000", "", "37172");
            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verifyNoInteractions(restTemplate);
        }

        @Test
        void stripsATrailingSlashFromTheConfiguredBaseUrl() {
            configure(true, BASE_URL + "/", "919000000000", "password", "37172");
            stubLogin("token");
            stubContactLookup("55");
            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", true))));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate).postForEntity(eq(SESSION_URL), any(), eq(Map.class));
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        void abortsWhenLoginReturnsANonSuccessStatus() {
            when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate, never()).postForEntity(eq(GRAPHQL_URL), any(), eq(Map.class));
        }

        @Test
        void abortsWhenLoginReturnsAnEmptyBody() {
            when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(null));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate, never()).postForEntity(eq(GRAPHQL_URL), any(), eq(Map.class));
        }

        @Test
        void abortsWhenTheLoginPayloadHasNoDataObject() {
            when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("unexpected", "shape")));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate, never()).postForEntity(eq(GRAPHQL_URL), any(), eq(Map.class));
        }

        @Test
        void abortsWhenTheAccessTokenIsBlank() {
            stubLogin("   ");

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate, never()).postForEntity(eq(GRAPHQL_URL), any(), eq(Map.class));
        }

        @Test
        void sendsTheConfiguredCredentialsTrimmed() {
            configure(true, BASE_URL, "  919000000000  ", "  password  ", "37172");
            stubLogin("token");
            stubContactLookup("55");
            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", true))));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForEntity(eq(SESSION_URL), request.capture(), eq(Map.class));
            @SuppressWarnings("unchecked")
            Map<String, Object> user = (Map<String, Object>)
                    ((Map<String, Object>) request.getValue().getBody()).get("user");
            assertThat(user).containsEntry("phone", "919000000000").containsEntry("password", "password");
        }
    }

    @Nested
    @DisplayName("contact resolution")
    class ContactResolution {

        @Test
        void abortsWhenNoCandidatePhoneResolvesToAContact() {
            stubLogin("token");
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("contactByPhone"), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of())));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate, never())
                    .postForEntity(eq(GRAPHQL_URL), argThatIsQuery("resumeContactFlow"), eq(Map.class));
        }

        @Test
        void retriesTheLookupWithAPlusPrefixedVariantOfThePhone() {
            stubLogin("token");
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("contactByPhone"), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("data", Map.of())))
                    .thenReturn(ResponseEntity.ok(Map.of("data",
                            Map.of("contactByPhone", Map.of("contact", Map.of("id", "55"))))));
            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", true))));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate).postForEntity(eq(GRAPHQL_URL), argThatIsQuery("resumeContactFlow"), eq(Map.class));
        }

        @Test
        void skipsACandidateWhoseLookupReturnedGraphQlErrors() {
            stubLogin("token");
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("contactByPhone"), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("errors",
                            List.of(Map.of("key", "phone", "message", "not found")))))
                    .thenReturn(ResponseEntity.ok(Map.of("data",
                            Map.of("contactByPhone", Map.of("contact", Map.of("id", "55"))))));
            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", true))));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate).postForEntity(eq(GRAPHQL_URL), argThatIsQuery("resumeContactFlow"), eq(Map.class));
        }

        @Test
        void skipsACandidateWhoseLookupReturnedANonSuccessStatus() {
            stubLogin("token");
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("contactByPhone"), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.BAD_GATEWAY))
                    .thenReturn(ResponseEntity.ok(Map.of("data",
                            Map.of("contactByPhone", Map.of("contact", Map.of("id", "55"))))));
            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", true))));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate).postForEntity(eq(GRAPHQL_URL), argThatIsQuery("resumeContactFlow"), eq(Map.class));
        }

        @Test
        void abortsWhenTheResolvedContactIdIsBlank() {
            stubLogin("token");
            stubContactLookup("   ");

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            verify(restTemplate, never())
                    .postForEntity(eq(GRAPHQL_URL), argThatIsQuery("resumeContactFlow"), eq(Map.class));
        }
    }

    @Nested
    @DisplayName("resume mutation")
    class ResumeMutation {

        @BeforeEach
        void stubHappyPrefix() {
            stubLogin("token");
            stubContactLookup("55");
        }

        @Test
        void sendsTheProcessingResultAsAJsonEncodedPayload() throws JsonProcessingException {
            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", true))));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            Map<String, Object> variables = variablesOf(capturedGraphQlRequest("resumeContactFlow"));
            assertThat(variables).containsEntry("flowId", "37172").containsEntry("contactId", "55");

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = new ObjectMapper()
                    .readValue(String.valueOf(variables.get("result")), Map.class);
            assertThat(payload)
                    .containsEntry("job_id", JOB_ID)
                    .containsEntry("success", true)
                    .containsEntry("message", "Reading recorded.")
                    .containsEntry("correlation_id", "corr-1")
                    .containsEntry("quality_status", "ACCEPTED");
        }

        @Test
        void sendsANullBearingPayloadWhenThereIsNoResult() throws JsonProcessingException {
            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", true))));

            service.resumeReadingsFlow(CONTACT, JOB_ID, null);

            Map<String, Object> variables = variablesOf(capturedGraphQlRequest("resumeContactFlow"));
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = new ObjectMapper()
                    .readValue(String.valueOf(variables.get("result")), Map.class);

            assertThat(payload).containsEntry("success", false).containsEntry("message", null);
        }

        @Test
        void sendsTheAccessTokenAsTheAuthorizationHeader() {
            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", true))));

            service.resumeReadingsFlow(CONTACT, JOB_ID, result());

            assertThat(capturedGraphQlRequest("resumeContactFlow").getHeaders().getFirst("Authorization"))
                    .isEqualTo("token");
        }

        @Test
        void toleratesANonSuccessStatusFromTheMutation() {
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("resumeContactFlow"), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));

            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();
        }

        @Test
        void toleratesAnEmptyMutationBody() {
            stubResumeMutation(null);

            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();
        }

        @Test
        void toleratesGraphQlErrorsInTheMutationResponse() {
            stubResumeMutation(Map.of("errors",
                    List.of(Map.of("key", "flow", "message", "flow not found"))));

            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();
        }

        @Test
        void toleratesGraphQlErrorsCarryingAlternateMessageKeys() {
            stubResumeMutation(Map.of("errors", List.of(
                    Map.of("key", "a", "msg", "alternate key"),
                    Map.of("key", "b", "details", "detail key"),
                    Map.of("key", "c"),
                    "a bare string error")));

            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();
        }

        @Test
        void toleratesAMutationThatReportedFailure() {
            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", false))));

            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();
        }

        @Test
        void toleratesAMutationResponseWithAnUnexpectedShape() {
            stubResumeMutation(Map.of("data", "not-a-map"));
            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();

            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", "not-a-map")));
            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();

            stubResumeMutation(Map.of("data", Map.of("resumeContactFlow", Map.of("success", "yes"))));
            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();
        }

        @Test
        void swallowsATransportFailureSoTheStoredReadingIsUnaffected() {
            when(restTemplate.postForEntity(eq(GRAPHQL_URL), argThatIsQuery("resumeContactFlow"), eq(Map.class)))
                    .thenThrow(new RestClientException("connection reset"));

            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();
        }

        @Test
        void swallowsALoginTransportFailure() {
            when(restTemplate.postForEntity(eq(SESSION_URL), any(), eq(Map.class)))
                    .thenThrow(new RestClientException("connection reset"));

            assertThatCode(() -> service.resumeReadingsFlow(CONTACT, JOB_ID, result()))
                    .doesNotThrowAnyException();
            verify(restTemplate, never()).postForEntity(eq(GRAPHQL_URL), any(), eq(Map.class));
        }
    }
}
