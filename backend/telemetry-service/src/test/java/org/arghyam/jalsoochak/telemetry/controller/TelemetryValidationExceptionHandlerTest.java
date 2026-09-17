package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.ReadingsApiResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bean-validation and malformed-body handling for the single-tenant readings API.
 *
 * <p>Assam's integration expects a {@code ReadingsApiResponse} envelope on the readings path (and on
 * its trailing-slash variant) rather than Spring's default error body, and a validation reject is
 * also published so analytics can still count the scheme as having reported.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TelemetryValidationExceptionHandler")
class TelemetryValidationExceptionHandlerTest {

    private static final String READINGS_PATH = "/api/v1/telemetry/readings";

    @Mock
    private TelemetrySubmissionAuditService auditService;
    @Mock
    private TelemetryEventPublisher eventPublisher;

    private TelemetryValidationExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TelemetryValidationExceptionHandler(auditService, eventPublisher);
        when(auditService.captureForAssamReading(any(), any()))
                .thenReturn(new TelemetrySubmissionAuditService.SubmissionAuditSnapshot(
                        "****0001", 7L, 1, LocalDate.of(2026, 3, 1)));
        when(auditService.captureForPhoneAndScheme(any(), any()))
                .thenReturn(new TelemetrySubmissionAuditService.SubmissionAuditSnapshot(
                        "****0001", 7L, 1, LocalDate.of(2026, 3, 1)));
    }

    private static MockHttpServletRequest request(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", servletPath);
        request.setServletPath(servletPath);
        return request;
    }

    /** Builds a validation failure carrying {@code target} and one rejected field. */
    private static MethodArgumentNotValidException validationFailure(Object target, String field, String message) {
        BindingResult bindingResult = new BeanPropertyBindingResult(target, "request");
        bindingResult.rejectValue(field, "NotBlank", message);
        return new MethodArgumentNotValidException((MethodParameter) null, bindingResult);
    }

    private static AssamReadingRequest assamRequest(String phone, String stateSchemeId, String centreSchemeId) {
        AssamReadingRequest request = new AssamReadingRequest();
        request.setPhoneNumber(phone);
        request.setStateSchemeId(stateSchemeId);
        request.setCentreSchemeId(centreSchemeId);
        return request;
    }

    @Nested
    @DisplayName("validation failures on the readings path")
    class ReadingsPathValidation {

        @ParameterizedTest(name = "path {0} returns the readings envelope")
        @ValueSource(strings = {READINGS_PATH, READINGS_PATH + "/"})
        void returnsTheReadingsEnvelopeForBothPathVariants(String path) {
            var response = handler.handleValidation(
                    validationFailure(assamRequest("919999900001", "S-1", null),
                            "phoneNumber", "phoneNumber is required"),
                    request(path));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isInstanceOf(ReadingsApiResponse.class);

            ReadingsApiResponse body = (ReadingsApiResponse) response.getBody();
            assertThat(body.isSuccess()).isFalse();
            assertThat(body.getData().getQualityStatus()).isEqualTo("REJECTED");
            assertThat(body.getData().getErrorCode()).isEqualTo(TelemetryErrorCode.VALIDATION_FAILED);
            assertThat(body.getData().getMessage()).isEqualTo("phoneNumber is required");
        }

        @Test
        void joinsMultipleValidationMessages() {
            BindingResult bindingResult = new BeanPropertyBindingResult(
                    assamRequest("919999900001", "S-1", null), "request");
            bindingResult.rejectValue("phoneNumber", "NotBlank", "phoneNumber is required");
            bindingResult.rejectValue("readingUrl", "NotBlank", "readingUrl is required");

            var response = handler.handleValidation(
                    new MethodArgumentNotValidException((MethodParameter) null, bindingResult),
                    request(READINGS_PATH));

            assertThat(((ReadingsApiResponse) response.getBody()).getData().getMessage())
                    .isEqualTo("phoneNumber is required; readingUrl is required");
        }

        @Test
        void fallsBackToAGenericMessageWhenNoErrorCarriesText() {
            BindingResult bindingResult = new BeanPropertyBindingResult(
                    assamRequest("919999900001", "S-1", null), "request");
            bindingResult.rejectValue("phoneNumber", "NotBlank", "");

            var response = handler.handleValidation(
                    new MethodArgumentNotValidException((MethodParameter) null, bindingResult),
                    request(READINGS_PATH));

            assertThat(((ReadingsApiResponse) response.getBody()).getData().getMessage())
                    .isEqualTo("Validation failed");
        }

        @Test
        void handlesAnUpdateReadingTargetThroughThePhoneScopedAudit() {
            UpdateReadingRequest target = new UpdateReadingRequest();
            target.setPhoneNumber("919999900001");
            target.setCorrelationId("corr-1");

            handler.handleValidation(
                    validationFailure(target, "confirmedReading", "confirmedReading is required"),
                    request(READINGS_PATH));

            verify(auditService).captureForPhoneAndScheme(eq("919999900001"), isNull());
        }

        @Test
        void toleratesATargetOfAnUnrecognisedType() {
            // A global (non-field) rejection on a request type the handler has no summariser for.
            BindingResult bindingResult = new BeanPropertyBindingResult(
                    new org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest(), "request");
            bindingResult.reject("Invalid", "bad");

            var response = handler.handleValidation(
                    new MethodArgumentNotValidException((MethodParameter) null, bindingResult),
                    request(READINGS_PATH));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("validation failures off the readings path")
    class OtherPathValidation {

        @Test
        void returnsAPlainMessageBody() {
            var response = handler.handleValidation(
                    validationFailure(assamRequest("919999900001", "S-1", null),
                            "phoneNumber", "phoneNumber is required"),
                    request("/api/v1/telemetry/other"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo(Map.of("message", "phoneNumber is required"));
        }

        @Test
        void doesNotPublishASubmissionRejectedEvent() {
            handler.handleValidation(
                    validationFailure(assamRequest("919999900001", "S-1", null),
                            "phoneNumber", "phoneNumber is required"),
                    request("/api/v1/telemetry/other"));

            verify(eventPublisher, never())
                    .publishSubmissionRejected(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("reported-metric publishing")
    class ReportedMetric {

        @Test
        void publishesTheRejectWhenAStateSchemeIdWasSubmitted() {
            handler.handleValidation(
                    validationFailure(assamRequest("919999900001", "S-1", null),
                            "phoneNumber", "phoneNumber is required"),
                    request(READINGS_PATH));

            verify(eventPublisher).publishSubmissionRejected(
                    isNull(), eq("S-1"), isNull(), isNull(),
                    eq("validation: phoneNumber is required"));
        }

        @Test
        void publishesTheRejectWhenOnlyACentreSchemeIdWasSubmitted() {
            handler.handleValidation(
                    validationFailure(assamRequest("919999900001", null, "C-1"),
                            "phoneNumber", "phoneNumber is required"),
                    request(READINGS_PATH));

            verify(eventPublisher).publishSubmissionRejected(
                    isNull(), isNull(), eq("C-1"), isNull(), any());
        }

        @Test
        void publishesNothingWhenNeitherSchemeIdWasSubmitted() {
            handler.handleValidation(
                    validationFailure(assamRequest("919999900001", "  ", null),
                            "phoneNumber", "phoneNumber is required"),
                    request(READINGS_PATH));

            // With no scheme id there is nothing for analytics to attribute the reject to.
            verify(eventPublisher, never())
                    .publishSubmissionRejected(any(), any(), any(), any(), any());
        }

        @Test
        void neverSendsAPhoneHashForAValidationReject() {
            handler.handleValidation(
                    validationFailure(assamRequest("919999900001", "S-1", null),
                            "phoneNumber", "phoneNumber is required"),
                    request(READINGS_PATH));

            verify(eventPublisher).publishSubmissionRejected(any(), any(), any(), isNull(), any());
        }

        @Test
        void worksWithoutAnEventPublisherConfigured() {
            var noPublisher = new TelemetryValidationExceptionHandler(auditService, null);

            var response = noPublisher.handleValidation(
                    validationFailure(assamRequest("919999900001", "S-1", null),
                            "phoneNumber", "phoneNumber is required"),
                    request(READINGS_PATH));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void worksWithoutAnAuditServiceConfigured() {
            var noAudit = new TelemetryValidationExceptionHandler(null, eventPublisher);

            var response = noAudit.handleValidation(
                    validationFailure(assamRequest("919999900001", "S-1", null),
                            "phoneNumber", "phoneNumber is required"),
                    request(READINGS_PATH));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("malformed request bodies")
    class MalformedBody {

        @ParameterizedTest(name = "path {0} returns the readings envelope")
        @ValueSource(strings = {READINGS_PATH, READINGS_PATH + "/"})
        void returnsTheReadingsEnvelopeOnTheReadingsPath(String path) {
            var response = handler.handleUnreadableJson(
                    new HttpMessageNotReadableException("Unexpected token", (org.springframework.http.HttpInputMessage) null),
                    request(path));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ReadingsApiResponse body = (ReadingsApiResponse) response.getBody();
            assertThat(body.isSuccess()).isFalse();
            assertThat(body.getData().getMessage()).isEqualTo("Malformed request body");
            assertThat(body.getData().getErrorCode()).isEqualTo(TelemetryErrorCode.MALFORMED_REQUEST);
        }

        @Test
        void returnsAPlainMessageBodyOffTheReadingsPath() {
            var response = handler.handleUnreadableJson(
                    new HttpMessageNotReadableException("Unexpected token", (org.springframework.http.HttpInputMessage) null),
                    request("/api/v1/telemetry/other"));

            assertThat(response.getBody()).isEqualTo(Map.of("message", "Malformed request body"));
        }

        @Test
        void sanitisesNewlinesOutOfTheLoggedParserMessage() {
            var response = handler.handleUnreadableJson(
                    new HttpMessageNotReadableException("line one\nline two",
                            (org.springframework.http.HttpInputMessage) null),
                    request(READINGS_PATH));

            // The response message is fixed; the point is that log forging via the parser message fails.
            assertThat(((ReadingsApiResponse) response.getBody()).getData().getMessage())
                    .isEqualTo("Malformed request body");
        }
    }

    @Nested
    @DisplayName("request path resolution")
    class PathResolution {

        @Test
        void fallsBackToTheRequestUriWhenTheServletPathIsEmpty() {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", READINGS_PATH);
            request.setServletPath("");
            request.setRequestURI(READINGS_PATH);

            var response = handler.handleUnreadableJson(
                    new HttpMessageNotReadableException("bad", (org.springframework.http.HttpInputMessage) null),
                    request);

            assertThat(response.getBody()).isInstanceOf(ReadingsApiResponse.class);
        }

        @Test
        void stripsTheContextPathFromTheRequestUri() {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/telemetry" + READINGS_PATH);
            request.setServletPath("");
            request.setContextPath("/telemetry");
            request.setRequestURI("/telemetry" + READINGS_PATH);

            var response = handler.handleUnreadableJson(
                    new HttpMessageNotReadableException("bad", (org.springframework.http.HttpInputMessage) null),
                    request);

            assertThat(response.getBody()).isInstanceOf(ReadingsApiResponse.class);
        }

        @Test
        void toleratesAMissingRequest() {
            ResponseEntity<?> response = handler.handleUnreadableJson(
                    new HttpMessageNotReadableException("bad", (org.springframework.http.HttpInputMessage) null),
                    null);

            assertThat(response.getBody()).isEqualTo(Map.of("message", "Malformed request body"));
        }

        @Test
        void toleratesARequestWithNoMethod() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServletPath(READINGS_PATH);
            request.setMethod("");

            var response = handler.handleValidation(
                    validationFailure(assamRequest("919999900001", "S-1", null),
                            "phoneNumber", "phoneNumber is required"),
                    request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
