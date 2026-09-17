package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ClosingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IssueReportRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.LocationReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedChannelRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedItemRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedLanguageRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedSchemeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.ClosingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.SelectionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GlificWebhookService} is the single entry point the controller talks to; it owns no logic
 * of its own and exists to route each Glific flow step to the workflow service that implements it.
 * These tests pin that routing so a step cannot silently be wired to the wrong collaborator.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificWebhookService — flow-step routing")
class GlificWebhookServiceTest {

    @Mock
    private GlificImageWorkflowService imageWorkflowService;
    @Mock
    private GlificMessageService messageService;
    @Mock
    private GlificSelectionService selectionService;
    @Mock
    private GlificMeterWorkflowService meterWorkflowService;

    @InjectMocks
    private GlificWebhookService service;

    private final IntroRequest introRequest = new IntroRequest();
    private final IntroResponse introResponse = IntroResponse.builder().success(true).build();
    private final CreateReadingResponse readingResponse = CreateReadingResponse.builder().success(true).build();

    // ---- image workflow ----

    @Test
    void processImageRoutesToTheImageWorkflow() {
        GlificWebhookRequest request = new GlificWebhookRequest();
        when(imageWorkflowService.processImage(request)).thenReturn(readingResponse);

        assertThat(service.processImage(request)).isSameAs(readingResponse);
        verify(imageWorkflowService).processImage(request);
    }

    @Test
    void processAssamReadingRoutesToTheImageWorkflowWithThePreferredTenant() {
        AssamReadingRequest request = new AssamReadingRequest();
        when(imageWorkflowService.processAssamReading(request, 17)).thenReturn(readingResponse);

        assertThat(service.processAssamReading(request, 17)).isSameAs(readingResponse);
        verify(imageWorkflowService).processAssamReading(request, 17);
    }

    // ---- plain messages ----

    @Test
    void introMessageRoutesToTheMessageService() {
        when(messageService.introMessage(introRequest)).thenReturn(introResponse);

        assertThat(service.introMessage(introRequest)).isSameAs(introResponse);
    }

    @Test
    void closingMessageRoutesToTheMessageService() {
        ClosingRequest request = new ClosingRequest();
        ClosingResponse response = ClosingResponse.builder().success(true).build();
        when(messageService.closingMessage(request)).thenReturn(response);

        assertThat(service.closingMessage(request)).isSameAs(response);
    }

    // ---- selection steps ----

    @Test
    void languageSelectionRoutesToTheSelectionService() {
        when(selectionService.languageSelectionMessage(introRequest)).thenReturn(introResponse);

        assertThat(service.languageSelectionMessage(introRequest)).isSameAs(introResponse);
    }

    @Test
    void selectedLanguageRoutesToTheSelectionService() {
        SelectedLanguageRequest request = new SelectedLanguageRequest();
        when(selectionService.selectedLanguageMessage(request)).thenReturn(introResponse);

        assertThat(service.selectedLanguageMessage(request)).isSameAs(introResponse);
    }

    @Test
    void channelSelectionRoutesToTheSelectionService() {
        when(selectionService.channelSelectionMessage(introRequest)).thenReturn(introResponse);

        assertThat(service.channelSelectionMessage(introRequest)).isSameAs(introResponse);
    }

    @Test
    void selectedChannelRoutesToTheSelectionService() {
        SelectedChannelRequest request = new SelectedChannelRequest();
        when(selectionService.selectedChannelMessage(request)).thenReturn(introResponse);

        assertThat(service.selectedChannelMessage(request)).isSameAs(introResponse);
    }

    @Test
    void schemeSelectionRoutesToTheSelectionService() {
        when(selectionService.schemeSelectionMessage(introRequest)).thenReturn(introResponse);

        assertThat(service.schemeSelectionMessage(introRequest)).isSameAs(introResponse);
    }

    @Test
    void selectedSchemeRoutesToTheSelectionService() {
        SelectedSchemeRequest request = new SelectedSchemeRequest();
        when(selectionService.selectedSchemeMessage(request)).thenReturn(introResponse);

        assertThat(service.selectedSchemeMessage(request)).isSameAs(introResponse);
    }

    @Test
    void itemSelectionRoutesToTheSelectionService() {
        when(selectionService.itemSelectionMessage(introRequest)).thenReturn(introResponse);

        assertThat(service.itemSelectionMessage(introRequest)).isSameAs(introResponse);
    }

    @Test
    void selectedItemRoutesToTheSelectionService() {
        SelectedItemRequest request = new SelectedItemRequest();
        SelectionResponse response = SelectionResponse.builder().success(true).build();
        when(selectionService.selectedItemMessage(request)).thenReturn(response);

        assertThat(service.selectedItemMessage(request)).isSameAs(response);
    }

    // ---- meter workflow steps ----

    @Test
    void meterChangePromptFromAnIntroRequestRoutesToTheMeterWorkflow() {
        when(meterWorkflowService.meterChangeMessage(introRequest)).thenReturn(introResponse);

        assertThat(service.meterChangeMessage(introRequest)).isSameAs(introResponse);
    }

    @Test
    void meterChangePromptFromAMeterChangeRequestRoutesToTheMeterWorkflow() {
        MeterChangeRequest request = new MeterChangeRequest();
        when(meterWorkflowService.meterChangeMessage(request)).thenReturn(introResponse);

        assertThat(service.meterChangeMessage(request)).isSameAs(introResponse);
    }

    @Test
    void takeMeterReadingRoutesToTheMeterWorkflow() {
        MeterChangeRequest request = new MeterChangeRequest();
        when(meterWorkflowService.takeMeterReadingMessage(request)).thenReturn(introResponse);

        assertThat(service.takeMeterReadingMessage(request)).isSameAs(introResponse);
    }

    @Test
    void meterChangeSubmitRoutesToTheMeterWorkflow() {
        MeterChangeRequest request = new MeterChangeRequest();
        when(meterWorkflowService.meterChangeSubmitMessage(request)).thenReturn(introResponse);

        assertThat(service.meterChangeSubmitMessage(request)).isSameAs(introResponse);
    }

    @Test
    void meterChangeReasonsRoutesToTheMeterWorkflow() {
        when(meterWorkflowService.meterChangeReasons(introRequest)).thenReturn("1. Broken");

        assertThat(service.meterChangeReasons(introRequest)).isEqualTo("1. Broken");
    }

    @Test
    void issueReportPromptRoutesToTheMeterWorkflow() {
        when(meterWorkflowService.issueReportPromptMessage(introRequest)).thenReturn(introResponse);

        assertThat(service.issueReportPromptMessage(introRequest)).isSameAs(introResponse);
    }

    @Test
    void issueReportSubmitRoutesToTheMeterWorkflow() {
        IssueReportRequest request = new IssueReportRequest();
        when(meterWorkflowService.issueReportSubmitMessage(request)).thenReturn(introResponse);

        assertThat(service.issueReportSubmitMessage(request)).isSameAs(introResponse);
    }

    @Test
    void issueReportTelemetryPromptRoutesToTheMeterWorkflow() {
        when(meterWorkflowService.issueReportTelemetryPromptMessage(introRequest)).thenReturn(introResponse);

        assertThat(service.issueReportTelemetryPromptMessage(introRequest)).isSameAs(introResponse);
    }

    @Test
    void issueReportTelemetrySubmitRoutesToTheMeterWorkflow() {
        IssueReportRequest request = new IssueReportRequest();
        when(meterWorkflowService.issueReportTelemetrySubmitMessage(request)).thenReturn(introResponse);

        assertThat(service.issueReportTelemetrySubmitMessage(request)).isSameAs(introResponse);
    }

    @Test
    void issueReportTelemetryReasonsRoutesToTheMeterWorkflow() {
        when(meterWorkflowService.issueReportTelemetryReasons(introRequest)).thenReturn("1. No power");

        assertThat(service.issueReportTelemetryReasons(introRequest)).isEqualTo("1. No power");
    }

    @Test
    void othersPromptRoutesToTheMeterWorkflow() {
        when(meterWorkflowService.othersPromptMessage(introRequest)).thenReturn(introResponse);

        assertThat(service.othersPromptMessage(introRequest)).isSameAs(introResponse);
    }

    @Test
    void othersSubmittedRoutesToTheMeterWorkflow() {
        IssueReportRequest request = new IssueReportRequest();
        when(meterWorkflowService.othersSubmittedMessage(request)).thenReturn(introResponse);

        assertThat(service.othersSubmittedMessage(request)).isSameAs(introResponse);
    }

    @Test
    void manualReadingRoutesToTheMeterWorkflow() {
        ManualReadingRequest request = new ManualReadingRequest();
        when(meterWorkflowService.manualReadingMessage(request)).thenReturn(readingResponse);

        assertThat(service.manualReadingMessage(request)).isSameAs(readingResponse);
    }

    @Test
    void locationReadingRoutesToTheMeterWorkflow() {
        LocationReadingRequest request = new LocationReadingRequest();
        when(meterWorkflowService.locationReadingMessage(request)).thenReturn(readingResponse);

        assertThat(service.locationReadingMessage(request)).isSameAs(readingResponse);
    }

    @Test
    void updatePreviousReadingRoutesToTheMeterWorkflow() {
        UpdatedPreviousReadingRequest request = new UpdatedPreviousReadingRequest();
        when(meterWorkflowService.updatePreviousReadingMessage(request)).thenReturn(readingResponse);

        assertThat(service.updatePreviousReadingMessage(request)).isSameAs(readingResponse);
    }
}
