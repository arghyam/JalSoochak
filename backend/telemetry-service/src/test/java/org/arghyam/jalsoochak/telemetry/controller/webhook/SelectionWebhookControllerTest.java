package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedChannelRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedItemRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedLanguageRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedSchemeRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.SelectionResponse;
import org.arghyam.jalsoochak.telemetry.service.GlificSelectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Endpoint behaviour of the selection webhooks: each passes the service response through, and falls
 * back to a body the chatbot flow can render when the service throws.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SelectionWebhookController — endpoints")
class SelectionWebhookControllerTest {

    private static final String CONTACT = "919999900001";
    private static final RuntimeException BOOM = new IllegalStateException("downstream failure");

    @Mock
    private GlificSelectionService selectionService;

    private SelectionWebhookController controller;

    private final IntroResponse okIntro = IntroResponse.builder().success(true).message("ok").build();

    @BeforeEach
    void setUp() {
        controller = new SelectionWebhookController(selectionService);
    }

    private static IntroRequest introRequest() {
        IntroRequest request = new IntroRequest();
        request.setContactId(CONTACT);
        return request;
    }

    @Test
    void languageSelectionPassesThroughAndFallsBack() {
        when(selectionService.languageSelectionMessage(any())).thenReturn(okIntro);
        assertThat(controller.languageSelection(introRequest()).getBody()).isSameAs(okIntro);

        when(selectionService.languageSelectionMessage(any())).thenThrow(BOOM);
        var response = controller.languageSelection(introRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    void selectedLanguagePassesThroughAndFallsBack() {
        SelectedLanguageRequest request = new SelectedLanguageRequest();
        request.setContactId(CONTACT);

        when(selectionService.selectedLanguageMessage(any())).thenReturn(okIntro);
        assertThat(controller.selectedLanguage(request).getBody()).isSameAs(okIntro);

        when(selectionService.selectedLanguageMessage(any())).thenThrow(BOOM);
        assertThat(controller.selectedLanguage(request).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void channelSelectionPassesThroughAndFallsBack() {
        when(selectionService.channelSelectionMessage(any())).thenReturn(okIntro);
        assertThat(controller.channelSelection(introRequest()).getBody()).isSameAs(okIntro);

        when(selectionService.channelSelectionMessage(any())).thenThrow(BOOM);
        assertThat(controller.channelSelection(introRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void selectedChannelPassesThroughAndFallsBack() {
        SelectedChannelRequest request = new SelectedChannelRequest();
        request.setContactId(CONTACT);

        when(selectionService.selectedChannelMessage(any())).thenReturn(okIntro);
        assertThat(controller.selectedChannel(request).getBody()).isSameAs(okIntro);

        when(selectionService.selectedChannelMessage(any())).thenThrow(BOOM);
        var response = controller.selectedChannel(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    void schemesPassesThroughAndFallsBack() {
        when(selectionService.schemeSelectionMessage(any())).thenReturn(okIntro);
        assertThat(controller.schemes(introRequest()).getBody()).isSameAs(okIntro);

        when(selectionService.schemeSelectionMessage(any())).thenThrow(BOOM);
        assertThat(controller.schemes(introRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void selectedSchemePassesThroughAndFallsBack() {
        SelectedSchemeRequest request = new SelectedSchemeRequest();
        request.setContactId(CONTACT);

        when(selectionService.selectedSchemeMessage(any())).thenReturn(okIntro);
        assertThat(controller.selectedScheme(request).getBody()).isSameAs(okIntro);

        when(selectionService.selectedSchemeMessage(any())).thenThrow(BOOM);
        var response = controller.selectedScheme(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("Scheme selection could not be saved.");
    }

    @Test
    void itemSelectionPassesThroughAndFallsBack() {
        when(selectionService.itemSelectionMessage(any())).thenReturn(okIntro);
        assertThat(controller.itemSelection(introRequest()).getBody()).isSameAs(okIntro);

        when(selectionService.itemSelectionMessage(any())).thenThrow(BOOM);
        assertThat(controller.itemSelection(introRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void selectedItemPassesThroughAndFallsBack() {
        SelectedItemRequest request = new SelectedItemRequest();
        request.setContactId(CONTACT);
        SelectionResponse ok = SelectionResponse.builder().success(true).build();

        when(selectionService.selectedItemMessage(any())).thenReturn(ok);
        assertThat(controller.selectedItem(request).getBody()).isSameAs(ok);

        when(selectionService.selectedItemMessage(any())).thenThrow(BOOM);
        assertThat(controller.selectedItem(request).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
