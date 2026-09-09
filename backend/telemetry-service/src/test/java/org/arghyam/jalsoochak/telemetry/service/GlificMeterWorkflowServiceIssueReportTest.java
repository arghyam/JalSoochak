package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.IssueReportRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificMeterWorkflowServiceIssueReportTest {

    @Mock
    private GlificOperatorContextService operatorContextService;

    @Mock
    private GlificLocalizationService localizationService;

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    @Mock
    private GlificMessageTemplatesService templatesService;

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;

    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;

    @Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private GlificMeterWorkflowService service;

    @Test
    void issueReportSubmitStoresReason2AsAnomalyAndNotInFlowReading() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        IntroResponse resp = service.issueReportSubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("2")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("meterNotWorking", resp.getSelected());

        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                eq("tenant_test"),
                eq(1L),
                eq(10L),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq("Meter not working"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
        verify(telemetryEventPublisher).publishOutageOrNonSubmissionReason(
                eq(1),
                eq(10L),
                eq(1L),
                org.mockito.ArgumentMatchers.any(),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq("Meter not working")
        );
        verify(telemetryEventPublisher).publishAnomalyRecorded(
                eq(1),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq(1L),
                eq(10L),
                isNull(),
                isNull(),
                isNull(),
                eq(0),
                isNull(),
                isNull(),
                eq(0),
                eq("Meter not working"),
                eq(AnomalyConstants.STATUS_OPEN),
                org.mockito.ArgumentMatchers.anyString()
        );
        verify(telemetryTenantRepository, never()).createIssueReportRecord(
                eq("tenant_test"),
                eq(10L),
                eq(1L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void issueReportSubmitStoresReason1InFlowReadingAndNotAsAnomaly() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = service.issueReportSubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("1")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("meterReplaced", resp.getSelected());
        assertEquals("please wait a second...", resp.getMessage());

        verify(telemetryTenantRepository).createIssueReportRecord(
                eq("tenant_test"),
                eq(10L),
                eq(1L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                eq("Meter Replaced")
        );
        verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void issueReportSubmitReturnsNoWaterSuppliedForReason5() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());
        when(templatesService.resolveScreenConfirmationTemplate(1, "ISSUE_REPORT", "english")).thenReturn(Optional.empty());
        when(tenantConfigRepository.findIssueReportConfirmationTemplate(1, "english")).thenReturn(Optional.empty());

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = service.issueReportSubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("5")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("noWaterSupplied", resp.getSelected());

        // Current behavior: reason "5" is treated as an anomaly selection (legacy numeric rule).
        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                eq("tenant_test"),
                eq(1L),
                eq(10L),
                eq(AnomalyConstants.TYPE_NO_WATER_SUPPLY),
                eq("No Water Supply"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
        verify(telemetryEventPublisher).publishOutageOrNonSubmissionReason(
                eq(1),
                eq(10L),
                eq(1L),
                org.mockito.ArgumentMatchers.any(),
                eq(AnomalyConstants.TYPE_NO_WATER_SUPPLY),
                eq("No Water Supply")
        );
        verify(telemetryTenantRepository, never()).createIssueReportRecord(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void issueReportSubmitUsesTemplateReasonKeyForSelected() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        List<GlificMessageTemplatesService.TemplateOption> templateReasons = List.of(
                new GlificMessageTemplatesService.TemplateOption("REASON_1", 1, java.util.Map.of("en", "Meter Replaced")),
                new GlificMessageTemplatesService.TemplateOption("REASON_2", 2, java.util.Map.of("en", "Incorrect Reading Entered Previously")),
                new GlificMessageTemplatesService.TemplateOption("REASON_3", 3, java.util.Map.of("en", "No Reading Submission")),
                new GlificMessageTemplatesService.TemplateOption("REASON_4", 4, java.util.Map.of("en", "No Water Supply"))
        );

        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(templateReasons);
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = service.issueReportSubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("3")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("noReadingSubmission", resp.getSelected());

        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                eq("tenant_test"),
                eq(1L),
                eq(10L),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq("No Reading Submission"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
    }

    @Test
    void issueReportSubmitRejectsOutOfRangeNumericSelection() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        IntroResponse resp = service.issueReportSubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("7")
                .build());

        assertNotNull(resp);
        assertEquals(false, resp.isSuccess());
        assertEquals("Please choose a number between 1 and 6.", resp.getMessage());

        verify(telemetryTenantRepository, never()).createIssueReportRecord(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
        verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void issueReportSubmitIgnoresDeletedChannelOutsideGlificReadingSubmission() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = service.issueReportSubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("1")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("meterReplaced", resp.getSelected());
        verify(telemetryTenantRepository).findFirstSchemeForUser("tenant_test", 1L);
    }

    @Test
    void othersSubmittedStoresFreeTextAsAnomalyAndNotInFlowReading() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(templatesService.resolveScreenConfirmationTemplate(1, "ISSUE_REPORT", "english")).thenReturn(Optional.empty());
        when(tenantConfigRepository.findIssueReportConfirmationTemplate(1, "english")).thenReturn(Optional.empty());

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = service.othersSubmittedMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("pipe leakage")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("others", resp.getSelected());

        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                eq("tenant_test"),
                eq(1L),
                eq(10L),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq("pipe leakage"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
        verify(telemetryEventPublisher).publishOutageOrNonSubmissionReason(
                eq(1),
                eq(10L),
                eq(1L),
                org.mockito.ArgumentMatchers.any(),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq("pipe leakage")
        );
        verify(telemetryTenantRepository, never()).createIssueReportRecord(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void telemetryIssueReportSubmitReturnsNoWaterSuppliedForReason4() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(tenantConfigRepository.findIssueReportConfirmationTemplate(1, "english")).thenReturn(Optional.empty());

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findSubDivisionalOfficerUserIdsForScheme("tenant_test", 10L)).thenReturn(List.of(99L));
        when(telemetryTenantRepository.upsertPendingIssueReportRecord(
                eq("tenant_test"),
                eq(10L),
                eq(1L),
                org.mockito.ArgumentMatchers.any(),
                eq("No Water Supply")
        )).thenReturn("corr-telemetry-1");

        IntroResponse resp = service.issueReportTelemetrySubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("4")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("noWaterSupplied", resp.getSelected());

        verify(telemetryEventPublisher).publishEscalationCreated(
                eq(1),
                eq(10L),
                eq(99L),
                eq(AnomalyConstants.TYPE_NO_WATER_SUPPLY),
                eq("No Water Supply"),
                eq("corr-telemetry-1"),
                eq(AnomalyConstants.STATUS_OPEN),
                isNull()
        );
        verify(telemetryEventPublisher).publishOutageOrNonSubmissionReason(
                eq(1),
                eq(10L),
                eq(1L),
                org.mockito.ArgumentMatchers.any(),
                eq(AnomalyConstants.TYPE_NO_WATER_SUPPLY),
                eq("No Water Supply")
        );
        verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(telemetryTenantRepository, never()).createIssueReportRecord(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    // ── Free-text character allowlist (CWE-20 remediation) ──────────────────────────────────────
    //
    // ISSUE_REASON_ALLOWED guards only input that matched no configured reason. The rejection is a
    // localised 200 rather than a 400 so a real operator typo does not stall the Glific flow, and
    // it happens before any write, so nothing is stored or published.

    private TelemetryOperatorWithSchema stubOperator() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );
        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        return operatorWithSchema;
    }

    private void stubLocalisedRejection(String defaultMessage) {
        when(localizationService.resolveLanguageKeyForContact("919999999999")).thenReturn("english");
        when(localizationService.resolveUserFacingErrorMessage(
                org.mockito.ArgumentMatchers.any(),
                eq(defaultMessage),
                eq("english")
        )).thenReturn("Issue reason can only contain letters, numbers, and spaces.");
    }

    private IntroResponse submitFreeText(String issueReason) {
        return service.issueReportSubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason(issueReason)
                .build());
    }

    @Test
    void issueReportSubmitRejectsHtmlTagsInFreeTextAndStoresNothing() {
        stubOperator();
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());
        stubLocalisedRejection("Issue report could not be saved.");

        IntroResponse resp = submitFreeText("<script>alert(1)</script>");

        assertNotNull(resp);
        assertEquals(false, resp.isSuccess());
        assertEquals("Issue reason can only contain letters, numbers, and spaces.", resp.getMessage());

        // The guard runs before findFirstSchemeForUser, so the tenant repository is never touched
        // at all — no row written, and no wasted lookup either.
        org.mockito.Mockito.verifyNoInteractions(telemetryTenantRepository);
        org.mockito.Mockito.verifyNoInteractions(telemetryEventPublisher);
    }

    @Test
    void issueReportSubmitRejectsPunctuationInFreeText() {
        // Deliberate: the allowlist admits no punctuation, and the localised copy says exactly
        // that. Pinned so the rule cannot be quietly relaxed without updating the copy too.
        stubOperator();
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());
        stubLocalisedRejection("Issue report could not be saved.");

        IntroResponse resp = submitFreeText("Motor burnt out, 3 days");

        assertEquals(false, resp.isSuccess());
        org.mockito.Mockito.verifyNoInteractions(telemetryTenantRepository);
    }

    @Test
    void issueReportSubmitAcceptsDevanagariFreeTextAndStoresItIntact() {
        stubOperator();
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = submitFreeText("पानी की आपूर्ति नहीं");

        assertEquals(true, resp.isSuccess());
        // Unmatched free text is stored as a no-submission anomaly, and the point of this test is
        // that the Devanagari survives byte-for-byte: not rejected by the allowlist, not escaped.
        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                eq("tenant_test"),
                eq(1L),
                eq(10L),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq("पानी की आपूर्ति नहीं"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
    }

    @Test
    void issueReportSubmitStillRejectsInvisibleFormatCharactersInFreeText() {
        // \p{M} admits combining marks so Indic scripts work, but zero-width and bidi-override
        // characters are category Cf, not M, and must stay refused — they are the invisible-text
        // abuse vector that a naive "allow all Unicode" fix would open up.
        stubOperator();
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());
        stubLocalisedRejection("Issue report could not be saved.");

        IntroResponse resp = submitFreeText("water‮reversed​hidden");

        assertEquals(false, resp.isSuccess());
        org.mockito.Mockito.verifyNoInteractions(telemetryTenantRepository);
    }

    @Test
    void issueReportSubmitRejectsAStandaloneCombiningMarkInFreeText() {
        // \p{M} is admitted only after a base character. A reason made of nothing but combining
        // marks is not a word in any script — it renders stacked over whatever text displays it —
        // and it passed the allowlist while \p{M} sat in the leading character class.
        stubOperator();
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());
        stubLocalisedRejection("Issue report could not be saved.");

        // U+093E DEVANAGARI VOWEL SIGN AA (Mc) and U+094D VIRAMA (Mn), with no consonant to attach
        // to. Written as escapes because a bare mark renders onto its neighbour in a diff.
        IntroResponse resp = submitFreeText("\u093E\u094D");

        assertEquals(false, resp.isSuccess());
        assertEquals("Issue reason can only contain letters, numbers, and spaces.", resp.getMessage());
        org.mockito.Mockito.verifyNoInteractions(telemetryTenantRepository);
        org.mockito.Mockito.verifyNoInteractions(telemetryEventPublisher);
    }

    @Test
    void issueReportSubmitRejectsFreeTextWhoseSecondWordStartsWithACombiningMark() {
        // The per-token rule: a leading base character on the first word must not license a
        // mark-only token after the space.
        stubOperator();
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());
        stubLocalisedRejection("Issue report could not be saved.");

        IntroResponse resp = submitFreeText("water \u094Dhidden");

        assertEquals(false, resp.isSuccess());
        org.mockito.Mockito.verifyNoInteractions(telemetryTenantRepository);
    }

    @Test
    void issueReportSubmitStillAcceptsDevanagariWhereEveryMarkFollowsItsConsonant() {
        // The other half of the per-token rule, and the one that matters: real Hindi is
        // Lo Mc Lo Mc … so tightening the pattern must not have re-broken it. "पानी" is
        // प + ा + न + ी — three of those four characters sit after a base.
        stubOperator();
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = submitFreeText("मीटर में पानी नहीं");

        assertEquals(true, resp.isSuccess());
        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                eq("tenant_test"),
                eq(1L),
                eq(10L),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq("मीटर में पानी नहीं"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
    }

    // ── Resolved-reason length (analytics VARCHAR(255) sink) ────────────────────────────────────
    //
    // IssueReportRequest caps what the operator types, so these cover the reasons that are not
    // operator input: labels resolved out of tenant configuration, which arrive unmeasured.

    @Test
    void issueReportSubmitRejectsAnOverLengthTenantConfiguredLabelInsteadOfTruncatingIt() {
        stubOperator();
        String overLength = "a".repeat(IssueReportRequest.MAX_ISSUE_REASON_LENGTH + 1);
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of(overLength));
        when(localizationService.resolveLanguageKeyForContact("919999999999")).thenReturn("english");
        // The over-length message is deliberately outside the localiser's allowlist: a tenant has
        // to fix the label, so the operator sees the caller's generic reply.
        when(localizationService.resolveUserFacingErrorMessage(
                org.mockito.ArgumentMatchers.any(),
                eq("Issue report could not be saved."),
                eq("english")
        )).thenReturn("Issue report could not be saved.");

        IntroResponse resp = submitFreeText("1");

        assertEquals(false, resp.isSuccess());
        assertEquals("Issue report could not be saved.", resp.getMessage());
        // Rejected before the write, and rejected whole — no truncated row, no partial reason
        // published to analytics.
        verify(telemetryTenantRepository, never()).createIssueReportRecord(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
        org.mockito.Mockito.verifyNoInteractions(telemetryEventPublisher);
    }

    @Test
    void issueReportSubmitAcceptsATenantLabelExactlyAtTheLengthCap() {
        // The boundary, so the guard cannot drift to >= and start rejecting a label that stores.
        stubOperator();
        String atCap = "a".repeat(IssueReportRequest.MAX_ISSUE_REASON_LENGTH);
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of(atCap));
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = submitFreeText("1");

        assertEquals(true, resp.isSuccess());
        verify(telemetryTenantRepository).createIssueReportRecord(
                eq("tenant_test"),
                eq(10L),
                eq(1L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                eq(atCap)
        );
    }

    @Test
    void telemetryIssueReportSubmitRejectsAnOverLengthSupplyOutageReasonName() {
        // The SUPPLY_OUTAGE_REASONS branch resolves its reason from tenant JSON and previously
        // applied no validation at all before publishing it.
        stubOperator();
        String overLength = "a".repeat(IssueReportRequest.MAX_ISSUE_REASON_LENGTH + 1);
        when(tenantConfigRepository.findConfigValue(1, "SUPPLY_OUTAGE_REASONS")).thenReturn(
                Optional.of("{\"reasons\":[{\"id\":\"r1\",\"name\":\"" + overLength + "\",\"sequenceOrder\":1}]}")
        );
        when(localizationService.resolveLanguageKeyForContact("919999999999")).thenReturn("english");
        when(localizationService.resolveUserFacingErrorMessage(
                org.mockito.ArgumentMatchers.any(),
                eq("Issue report could not be saved."),
                eq("english")
        )).thenReturn("Issue report could not be saved.");

        IntroResponse resp = service.issueReportTelemetrySubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("1")
                .build());

        assertEquals(false, resp.isSuccess());
        verify(telemetryTenantRepository, never()).upsertPendingIssueReportRecord(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        );
        org.mockito.Mockito.verifyNoInteractions(telemetryEventPublisher);
    }

    @Test
    void issueReportSubmitAcceptsATenantConfiguredLabelContainingPunctuation() {
        // The regression test that protects the matchedConfiguredReason discriminator. Tenant
        // labels come from the database and may contain punctuation the allowlist forbids;
        // simplifying the guard to reasons.contains(...) or dropping the flag would start
        // rejecting valid menu picks.
        stubOperator();
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english"))
                .thenReturn(List.of("Motor burnt out, needs repair", "Pipe leak - urgent"));
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = submitFreeText("1");

        assertEquals(true, resp.isSuccess());
        verify(telemetryTenantRepository).createIssueReportRecord(
                eq("tenant_test"),
                eq(10L),
                eq(1L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                eq("Motor burnt out, needs repair")
        );
    }

    @Test
    void issueReportSubmitAcceptsAPunctuatedTenantLabelSelectedByItsExactText() {
        // Same discriminator, reached through the label-equality branch of resolveSelection
        // rather than the numeric-index branch.
        stubOperator();
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english"))
                .thenReturn(List.of("Motor burnt out, needs repair", "Pipe leak - urgent"));
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = submitFreeText("Pipe leak - urgent");

        assertEquals(true, resp.isSuccess());
        // Position 2 maps to the "meterNotWorking" selection key, which routes to the anomaly
        // sink rather than flow_reading_table — the punctuated label is what matters here.
        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                eq("tenant_test"),
                eq(1L),
                eq(10L),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq("Pipe leak - urgent"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
    }

    @Test
    void othersSubmittedNowAcceptsDevanagariFreeText() {
        // Behaviour change on an endpoint outside the reported finding, and the reason \p{M} was
        // added: this path already enforced ISSUE_REASON_ALLOWED, so before the fix a Hindi
        // "other issue" was rejected outright. Guards against a regression to letters-only.
        stubOperator();
        when(templatesService.resolveScreenConfirmationTemplate(1, "ISSUE_REPORT", "english"))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findIssueReportConfirmationTemplate(1, "english"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        IntroResponse resp = service.othersSubmittedMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("पानी की आपूर्ति नहीं")
                .build());

        assertEquals(true, resp.isSuccess());
        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                eq("tenant_test"),
                eq(1L),
                eq(10L),
                eq(AnomalyConstants.TYPE_NO_SUBMISSION),
                eq("पानी की आपूर्ति नहीं"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
    }

    @Test
    void telemetryIssueReportSubmitRejectsHtmlTagsInFreeTextAndStoresNothing() {
        stubOperator();
        stubLocalisedRejection("Issue report could not be saved.");

        IntroResponse resp = service.issueReportTelemetrySubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("<script>alert(1)</script>")
                .build());

        assertEquals(false, resp.isSuccess());
        assertEquals("Issue reason can only contain letters, numbers, and spaces.", resp.getMessage());
        org.mockito.Mockito.verifyNoInteractions(telemetryTenantRepository);
        org.mockito.Mockito.verifyNoInteractions(telemetryEventPublisher);
    }

    @Test
    void telemetryIssueReportSubmitWithConfiguredReasonsStillRejectsFreeTextAsInvalidChoice() {
        // The SUPPLY_OUTAGE_REASONS branch requires a numeric index, so it never reaches the
        // allowlist. Pinned so the new guard did not change this endpoint's other path.
        stubOperator();
        when(tenantConfigRepository.findConfigValue(1, "SUPPLY_OUTAGE_REASONS")).thenReturn(
                Optional.of("{\"reasons\":[{\"id\":\"r1\",\"name\":\"No water\",\"sequenceOrder\":1}]}")
        );

        IntroResponse resp = service.issueReportTelemetrySubmitMessage(IssueReportRequest.builder()
                .contactId("919999999999")
                .issueReason("<script>alert(1)</script>")
                .build());

        assertEquals(false, resp.isSuccess());
        assertEquals("invalid choice, please restart the flow", resp.getMessage());
        org.mockito.Mockito.verifyNoInteractions(telemetryTenantRepository);
    }
}
