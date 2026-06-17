package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.ClosingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
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
import org.springframework.stereotype.Service;

@Service
public class GlificWebhookService {

    private final GlificImageWorkflowService imageWorkflowService;
    private final GlificMessageService messageService;
    private final GlificSelectionService selectionService;
    private final GlificMeterWorkflowService meterWorkflowService;

    public GlificWebhookService(GlificImageWorkflowService imageWorkflowService,
                                GlificMessageService messageService,
                                GlificSelectionService selectionService,
                                GlificMeterWorkflowService meterWorkflowService) {
        this.imageWorkflowService = imageWorkflowService;
        this.messageService = messageService;
        this.selectionService = selectionService;
        this.meterWorkflowService = meterWorkflowService;
    }

    public CreateReadingResponse processImage(GlificWebhookRequest glificWebhookRequest) {
        return imageWorkflowService.processImage(glificWebhookRequest);
    }

    public void validateSelectedChannelForGlificReading(GlificWebhookRequest glificWebhookRequest) {
        imageWorkflowService.validateSelectedChannelForGlificReading(glificWebhookRequest);
    }

    public CreateReadingResponse processAssamReading(AssamReadingRequest request, Integer preferredTenantId) {
        return imageWorkflowService.processAssamReading(request, preferredTenantId);
    }

    public IntroResponse introMessage(IntroRequest introRequest) {
        return messageService.introMessage(introRequest);
    }

    public ClosingResponse closingMessage(ClosingRequest closingRequest) {
        return messageService.closingMessage(closingRequest);
    }

    public IntroResponse languageSelectionMessage(IntroRequest request) {
        return selectionService.languageSelectionMessage(request);
    }

    public IntroResponse selectedLanguageMessage(SelectedLanguageRequest request) {
        return selectionService.selectedLanguageMessage(request);
    }

    public IntroResponse channelSelectionMessage(IntroRequest request) {
        return selectionService.channelSelectionMessage(request);
    }

    public IntroResponse selectedChannelMessage(SelectedChannelRequest request) {
        return selectionService.selectedChannelMessage(request);
    }

    public IntroResponse schemeSelectionMessage(IntroRequest request) {
        return selectionService.schemeSelectionMessage(request);
    }

    public IntroResponse selectedSchemeMessage(SelectedSchemeRequest request) {
        return selectionService.selectedSchemeMessage(request);
    }

    public IntroResponse itemSelectionMessage(IntroRequest request) {
        return selectionService.itemSelectionMessage(request);
    }

    public SelectionResponse selectedItemMessage(SelectedItemRequest request) {
        return selectionService.selectedItemMessage(request);
    }

    public IntroResponse meterChangeMessage(IntroRequest request) {
        return meterWorkflowService.meterChangeMessage(request);
    }

    public IntroResponse meterChangeMessage(MeterChangeRequest request) {
        return meterWorkflowService.meterChangeMessage(request);
    }

    public IntroResponse takeMeterReadingMessage(MeterChangeRequest request) {
        return meterWorkflowService.takeMeterReadingMessage(request);
    }

    public IntroResponse issueReportPromptMessage(IntroRequest request) {
        return meterWorkflowService.issueReportPromptMessage(request);
    }

    public IntroResponse issueReportSubmitMessage(IssueReportRequest request) {
        return meterWorkflowService.issueReportSubmitMessage(request);
    }

    public IntroResponse issueReportTelemetryPromptMessage(IntroRequest request) {
        return meterWorkflowService.issueReportTelemetryPromptMessage(request);
    }

    public IntroResponse issueReportTelemetrySubmitMessage(IssueReportRequest request) {
        return meterWorkflowService.issueReportTelemetrySubmitMessage(request);
    }

    public String issueReportTelemetryReasons(IntroRequest request) {
        return meterWorkflowService.issueReportTelemetryReasons(request);
    }

    public String meterChangeReasons(IntroRequest request) {
        return meterWorkflowService.meterChangeReasons(request);
    }

    public IntroResponse meterChangeSubmitMessage(MeterChangeRequest request) {
        return meterWorkflowService.meterChangeSubmitMessage(request);
    }

    public IntroResponse othersPromptMessage(IntroRequest request) {
        return meterWorkflowService.othersPromptMessage(request);
    }

    public IntroResponse othersSubmittedMessage(IssueReportRequest request) {
        return meterWorkflowService.othersSubmittedMessage(request);
    }

    public CreateReadingResponse manualReadingMessage(ManualReadingRequest request) {
        return meterWorkflowService.manualReadingMessage(request);
    }

    public CreateReadingResponse locationReadingMessage(LocationReadingRequest request) {
        return meterWorkflowService.locationReadingMessage(request);
    }

    public CreateReadingResponse updatePreviousReadingMessage(UpdatedPreviousReadingRequest request) {
        return meterWorkflowService.updatePreviousReadingMessage(request);
    }
}
